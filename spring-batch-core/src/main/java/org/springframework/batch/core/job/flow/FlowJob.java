/*
 * Copyright 2006-2007 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.batch.core.job.flow;

import java.util.Collection;
import java.util.HashSet;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobInterruptedException;
import org.springframework.batch.core.StartLimitExceededException;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.AbstractJob;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.batch.core.step.StepHolder;

/**
 * Implementation of the {@link Job} interface that allows for complex flows of
 * steps, rather than requiring sequential execution. In general, this job
 * implementation was designed to be used behind a parser, allowing for a
 * namespace to abstract away details.
 * 
 * @author Dave Syer
 * @since 2.0
 */
public class FlowJob extends AbstractJob {

	private Flow flow;

	/**
	 * Create a {@link FlowJob} with null name and no flow (invalid state).
	 */
	public FlowJob() {
		super();
	}

	/**
	 * Create a {@link FlowJob} with provided name and no flow (invalid state).
	 */
	public FlowJob(String name) {
		super(name);
	}

	/**
	 * Public setter for the flow.
	 * 
	 * @param flow the flow to set
	 */
	public void setFlow(Flow flow) {
		this.flow = flow;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Step getStep(String stepName) {
		State state = this.flow.getState(stepName);
		if (state instanceof StepHolder) {
			return ((StepHolder) state).getStep();
		}
		return null;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Collection<String> getStepNames() {
		Collection<String> steps = new HashSet<String>();
		for (State state : flow.getStates()) {
			if (state instanceof StepHolder) {
				steps.add(state.getName());
			}
		}
		return steps;
	}

	/**
	 * @see AbstractJob#doExecute(JobExecution)
	 */
	@Override
	protected void doExecute(final JobExecution execution) throws JobExecutionException {
		try {
			JobFlowExecutor executor = new JobFlowExecutor(execution);
			executor.updateJobExecutionStatus(flow.start(executor).getStatus());
		}
		catch (FlowExecutionException e) {
			if (e.getCause() instanceof JobExecutionException) {
				throw (JobExecutionException) e.getCause();
			}
			throw new JobExecutionException("Flow execution ended unexpectedly", e);
		}
	}

	/**
	 * @author Dave Syer
	 * 
	 */
	private class JobFlowExecutor implements FlowExecutor {

		private final ThreadLocal<StepExecution> stepExecutionHolder = new ThreadLocal<StepExecution>();

		private final JobExecution execution;

		private ExitStatus exitStatus = ExitStatus.EXECUTING;

		/**
		 * @param execution
		 */
		private JobFlowExecutor(JobExecution execution) {
			this.execution = execution;
			stepExecutionHolder.set(null);
		}

		public String executeStep(Step step) throws JobInterruptedException, JobRestartException,
				StartLimitExceededException {
			StepExecution stepExecution = handleStep(step, execution);
			stepExecutionHolder.set(stepExecution);
			return stepExecution == null ? ExitStatus.COMPLETED.getExitCode() : stepExecution.getExitStatus()
					.getExitCode();
		}

		public void abandonStepExecution() {
			StepExecution lastStepExecution = stepExecutionHolder.get();
			if (lastStepExecution != null && lastStepExecution.getStatus().isGreaterThan(BatchStatus.STOPPING)) {
				lastStepExecution.upgradeStatus(BatchStatus.ABANDONED);
				updateStepExecution(lastStepExecution);
			}
		}

		public void updateJobExecutionStatus(FlowExecutionStatus status) {
			execution.setStatus(findBatchStatus(status));
			exitStatus = exitStatus.and(new ExitStatus(status.getName()));
			execution.setExitStatus(exitStatus);
		}

		public JobExecution getJobExecution() {
			return execution;
		}

		public StepExecution getStepExecution() {
			return stepExecutionHolder.get();
		}

		public void close(FlowExecution result) {
			stepExecutionHolder.set(null);
		}

		public boolean isRestart() {
			if (getStepExecution() != null && getStepExecution().getStatus() == BatchStatus.ABANDONED) {
				/*
				 * This is assumed to be the last step execution and it was
				 * marked abandoned, so we are in a restart of a stopped step.
				 * TODO: mark the step execution in some more definitive way?
				 */
				return true;
			}
			return execution.getStepExecutions().isEmpty();
		}

		public void addExitStatus(String code) {
			exitStatus = exitStatus.and(new ExitStatus(code));
		}

		/**
		 * @param status
		 * @return
		 */
		private BatchStatus findBatchStatus(FlowExecutionStatus status) {
			for (BatchStatus batchStatus : BatchStatus.values()) {
				if (status.getName().startsWith(batchStatus.toString())) {
					return batchStatus;
				}
			}
			return BatchStatus.UNKNOWN;
		}

	}

}
