/*******************************************************************************
 * Copyright (c) 2011 Red Hat and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     David Green <david.green@tasktop.com> - initial contribution
 *     Christian Trutz <christian.trutz@gmail.com> - initial contribution
 *     Chris Aniszczyk <caniszczyk@gmail.com> - initial contribution
 *******************************************************************************/
package org.eclipse.mylyn.github.internal;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.mylyn.tasks.core.IRepositoryPerson;
import org.eclipse.mylyn.tasks.core.ITaskMapping;
import org.eclipse.mylyn.tasks.core.RepositoryResponse;
import org.eclipse.mylyn.tasks.core.RepositoryResponse.ResponseKind;
import org.eclipse.mylyn.tasks.core.TaskRepository;
import org.eclipse.mylyn.tasks.core.data.AbstractTaskDataHandler;
import org.eclipse.mylyn.tasks.core.data.TaskAttribute;
import org.eclipse.mylyn.tasks.core.data.TaskAttributeMapper;
import org.eclipse.mylyn.tasks.core.data.TaskAttributeMetaData;
import org.eclipse.mylyn.tasks.core.data.TaskCommentMapper;
import org.eclipse.mylyn.tasks.core.data.TaskData;
import org.eclipse.mylyn.tasks.core.data.TaskOperation;

public class GitHubTaskDataHandler extends AbstractTaskDataHandler {

	private static final String DATA_VERSION = "1";
	/**
	 * 
	 */
	private GitHubTaskAttributeMapper taskAttributeMapper = null;
	private final GitHubRepositoryConnector connector;

	public GitHubTaskDataHandler(GitHubRepositoryConnector connector) {
		this.connector = connector;
	}

	@Override
	public TaskAttributeMapper getAttributeMapper(TaskRepository taskRepository) {
		if (this.taskAttributeMapper == null)
			this.taskAttributeMapper = new GitHubTaskAttributeMapper(
					taskRepository);
		return this.taskAttributeMapper;
	}

	public TaskData createPartialTaskData(TaskRepository repository,
			IProgressMonitor monitor, String user, String project, Issue issue) {

		String key = Integer.toString(issue.getNumber());
		TaskData data = new TaskData(getAttributeMapper(repository),
				GitHubRepositoryConnector.KIND, repository.getRepositoryUrl(),
				key);
		data.setVersion(DATA_VERSION);

		createOperations(data, issue);

		createAttribute(data, GitHubTaskAttributes.KEY, key);
		createAttribute(data, GitHubTaskAttributes.TITLE, issue.getTitle());
		createAttribute(data, GitHubTaskAttributes.BODY, issue.getBody());
		createAttribute(data, GitHubTaskAttributes.STATUS, issue.getState());
		createAttribute(data, GitHubTaskAttributes.CREATION_DATE,
				issue.getCreatedAt());
		createAttribute(data, GitHubTaskAttributes.MODIFICATION_DATE,
				issue.getUpdatedAt());
		createAttribute(data, GitHubTaskAttributes.CLOSED_DATE,
				issue.getClosedAt());
		createAttribute(data, GitHubTaskAttributes.REPORTER, issue.getUser(),
				repository);
		createAttribute(data, GitHubTaskAttributes.ASSIGNEE,
				issue.getAssignee(), repository);
		createAttribute(data, GitHubTaskAttributes.COMMENT_NEW, "");
		createAttribute(data, GitHubTaskAttributes.LABELS, issue.getLabels());

		if (isPartial(data)) {
			data.setPartial(true);
		}

		return data;
	}

	private boolean isPartial(TaskData data) {
		for (GitHubTaskAttributes attribute : GitHubTaskAttributes.values()) {
			if (attribute.isRequiredForFullTaskData()) {
				TaskAttribute taskAttribute = data.getRoot().getAttribute(
						attribute.getId());
				if (taskAttribute == null) {
					return true;
				}
			}
		}
		return false;
	}

	private void createOperations(TaskData data, Issue issue) {
		TaskAttribute operationAttribute = data.getRoot().createAttribute(
				TaskAttribute.OPERATION);
		operationAttribute.getMetaData().setType(TaskAttribute.TYPE_OPERATION);

		if (!data.isNew()) {
			if (issue.getState() != null) {
				addOperation(data, issue, GitHubTaskOperation.LEAVE, true);
				if (issue.getState().equals("open")) {
					addOperation(data, issue, GitHubTaskOperation.CLOSE, false);
				} else if (issue.getState().equals("closed")) {
					addOperation(data, issue, GitHubTaskOperation.REOPEN, false);
				}
			}
		}
	}

	private void addOperation(TaskData data, Issue issue,
			GitHubTaskOperation operation, boolean asDefault) {
		TaskAttribute attribute = data.getRoot().createAttribute(
				TaskAttribute.PREFIX_OPERATION + operation.getId());
		String label = createOperationLabel(issue, operation);
		TaskOperation.applyTo(attribute, operation.getId(), label);

		if (asDefault) {
			TaskAttribute operationAttribute = data.getRoot().getAttribute(
					TaskAttribute.OPERATION);
			TaskOperation.applyTo(operationAttribute, operation.getId(), label);
		}
	}

	private String createOperationLabel(Issue issue,
			GitHubTaskOperation operation) {
		return operation == GitHubTaskOperation.LEAVE ? operation.getLabel()
				+ issue.getState() : operation.getLabel();
	}

	public TaskData createTaskData(TaskRepository repository,
			IProgressMonitor monitor, String user, String project, Issue issue,
			List<Comment> comments) {
		TaskData taskData = createPartialTaskData(repository, monitor, user,
				project, issue);
		taskData.setPartial(false);

		if (comments != null && !comments.isEmpty()) {
			int count = 1;
			TaskAttribute root = taskData.getRoot();
			for (Comment comment : comments) {
				TaskCommentMapper commentMapper = new TaskCommentMapper();
				User author = comment.getUser();
				IRepositoryPerson authorPerson = repository.createPerson(author
						.getLogin());
				authorPerson.setName(author.getName());
				commentMapper.setAuthor(authorPerson);
				commentMapper.setCreationDate(comment.getCreatedAt());
				commentMapper.setText(comment.getBody());
				commentMapper.setCommentId(comment.getUrl());
				commentMapper.setNumber(count);

				TaskAttribute attribute = root
						.createAttribute(TaskAttribute.PREFIX_COMMENT + count);
				commentMapper.applyTo(attribute);
				count++;
			}
		}

		return taskData;
	}

	private GitHubIssue createIssue(TaskData taskData) {
		GitHubIssue issue = new GitHubIssue();
		if (!taskData.isNew()) {
			issue.setNumber(taskData.getTaskId());
		}
		issue.setBody(getAttributeValue(taskData, GitHubTaskAttributes.BODY));
		issue.setTitle(getAttributeValue(taskData, GitHubTaskAttributes.TITLE));
		issue.setState(getAttributeValue(taskData, GitHubTaskAttributes.STATUS));
		issue.setComment_new(getAttributeValue(taskData,
				GitHubTaskAttributes.COMMENT_NEW));
		return issue;
	}

	private String getAttributeValue(TaskData taskData,
			GitHubTaskAttributes attr) {
		TaskAttribute attribute = taskData.getRoot().getAttribute(attr.getId());
		return attribute == null ? null : attribute.getValue();
	}

	private TaskAttribute createAttribute(TaskData data,
			GitHubTaskAttributes attribute) {
		TaskAttribute attr = data.getRoot().createAttribute(attribute.getId());
		TaskAttributeMetaData metaData = attr.getMetaData();
		metaData.defaults().setType(attribute.getType())
				.setKind(attribute.getKind()).setLabel(attribute.getLabel())
				.setReadOnly(attribute.isReadOnly());
		return attr;
	}

	private void createAttribute(TaskData data, GitHubTaskAttributes attribute,
			String value) {
		TaskAttribute attr = createAttribute(data, attribute);
		if (value != null) {
			data.getAttributeMapper().setValue(attr, value);
		}
	}

	private void createAttribute(TaskData data, GitHubTaskAttributes attribute,
			Date value) {
		TaskAttribute attr = createAttribute(data, attribute);
		if (value != null) {
			data.getAttributeMapper().setDateValue(attr, value);
		}
	}

	private void createAttribute(TaskData data, GitHubTaskAttributes attribute,
			User value, TaskRepository repository) {
		TaskAttribute attr = createAttribute(data, attribute);
		if (value != null) {
			IRepositoryPerson person = repository
					.createPerson(value.getLogin());
			person.setName(value.getName());
			data.getAttributeMapper().setRepositoryPerson(attr, person);
		}
	}

	private void createAttribute(TaskData data, GitHubTaskAttributes attribute,
			List<Label> values) {
		TaskAttribute attr = createAttribute(data, attribute);
		if (values != null) {
			List<String> labels = new LinkedList<String>();
			for (Label label : values) {
				labels.add(label.getName());
			}
			data.getAttributeMapper().setValues(attr, labels);
		}
	}

	@Override
	public boolean initializeTaskData(TaskRepository repository, TaskData data,
			ITaskMapping initializationData, IProgressMonitor monitor)
			throws CoreException {

		data.setVersion(DATA_VERSION);

		for (GitHubTaskAttributes attr : GitHubTaskAttributes.values()) {
			if (attr.isInitTask()) {
				createAttribute(data, attr, (String) null);
			}
		}

		return true;
	}

	@Override
	public RepositoryResponse postTaskData(TaskRepository repository,
			TaskData taskData, Set<TaskAttribute> oldAttributes,
			IProgressMonitor monitor) throws CoreException {

		GitHubIssue issue = createIssue(taskData);
		String user = GitHub.computeTaskRepositoryUser(repository.getUrl());
		String repo = GitHub.computeTaskRepositoryProject(repository.getUrl());
		try {

			GitHubService service = connector.getService();
			GitHubCredentials credentials = GitHubCredentials
					.create(repository);
			if (taskData.isNew()) {
				issue = service.openIssue(user, repo, issue, credentials);
			} else {

				// handle new comment
				if (issue.getComment_new() != null) {
					if (!"".equals(issue.getComment_new())) {
						service.addComment(user, repo, issue, credentials);
					}
				}

				TaskAttribute operationAttribute = taskData.getRoot()
						.getAttribute(TaskAttribute.OPERATION);
				GitHubTaskOperation operation = null;
				if (operationAttribute != null) {
					String opId = operationAttribute.getValue();
					operation = GitHubTaskOperation.fromId(opId);

				}
				if (operation != GitHubTaskOperation.LEAVE) {
					service.editIssue(user, repo, issue, credentials);
					switch (operation) {
					case REOPEN:
						service.reopenIssue(user, repo, issue, credentials);
						break;
					case CLOSE:
						service.closeIssue(user, repo, issue, credentials);
						break;
					default:
						throw new IllegalStateException("not implemented: "
								+ operation);
					}
				} else {
					service.editIssue(user, repo, issue, credentials);
				}

			}
			return new RepositoryResponse(
					taskData.isNew() ? ResponseKind.TASK_CREATED
							: ResponseKind.TASK_UPDATED, issue.getNumber());
		} catch (GitHubServiceException e) {
			throw new CoreException(GitHub.createErrorStatus(e));
		}

	}

}