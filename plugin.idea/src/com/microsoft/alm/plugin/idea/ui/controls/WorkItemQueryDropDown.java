// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See License.txt in the project root.

package com.microsoft.alm.plugin.idea.ui.controls;

import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Constraints;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareAction;
import com.microsoft.alm.plugin.authentication.AuthHelper;
import com.microsoft.alm.plugin.context.ServerContext;
import com.microsoft.alm.plugin.context.ServerContextManager;
import com.microsoft.alm.plugin.idea.resources.TfPluginBundle;
import com.microsoft.alm.plugin.idea.ui.workitem.WorkItemHelper;
import com.microsoft.alm.plugin.idea.utils.IdeaHelper;
import com.microsoft.alm.plugin.idea.utils.TfGitHelper;
import com.microsoft.alm.plugin.operations.Operation;
import com.microsoft.alm.plugin.operations.WorkItemQueriesLookupOperation;
import com.microsoft.alm.workitemtracking.webapi.models.QueryHierarchyItem;
import git4idea.repo.GitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JLabel;
import java.awt.event.ActionEvent;

public class WorkItemQueryDropDown extends FilterDropDown { //JPanel
    private static final Logger logger = LoggerFactory.getLogger(WorkItemQueryDropDown.class);

    public static final String CMD_QUERY_COMBO_BOX_CHANGED = "cmdQueryComboBoxChanged";

    private final QueryAction defaultQuery;
    private final GitRepository gitRepository;
    private final LoadingAction loadingAction;
    private final WorkItemQueriesLookupOperation.QueryInputs queryOperationInput;

    private QueryAction selectedQuery;

    public WorkItemQueryDropDown(final GitRepository gitRepository) {
        super();
        this.gitRepository = gitRepository;
        this.defaultQuery = new QueryAction(TfPluginBundle.message(TfPluginBundle.KEY_VCS_WIT_QUERY_DEFAULT_QUERY), WorkItemHelper.getAssignedToMeQuery());
        this.loadingAction = new LoadingAction();
        this.queryOperationInput = new WorkItemQueriesLookupOperation.QueryInputs(WorkItemQueriesLookupOperation.QueryRootDirectories.MY_QUERIES);

        populateDropDownMenu();
        initializeUI();
    }

    protected void initializeUI() {
        super.initializeUI(TfPluginBundle.message(TfPluginBundle.KEY_VCS_WIT_QUERY_TITLE), new JLabel() {
            @Override
            public String getText() {
                return selectedQuery.queryName;
            }
        });
    }

    protected ActionGroup populateDropDownMenu() {
        if (!isInitialized) {
            // add initial items to menu
            group.add(defaultQuery, Constraints.FIRST);
            group.addSeparator(TfPluginBundle.message(TfPluginBundle.KEY_VCS_WIT_QUERY_SEPARATOR_MY_QUERIES));
            group.add(loadingAction, Constraints.LAST);

            // persist an existing selected query if there is one
            selectedQuery = selectedQuery == null ? defaultQuery : selectedQuery;

            // add menu items from server
            addQueriesFromServer(group);

            isInitialized = true;
        }
        return group;
    }

    public String getSelectedResults() {
        return selectedQuery.wiql;
    }

    private void addQueriesFromServer(final DefaultActionGroup group) {
        final String gitRemoteUrl = TfGitHelper.getTfGitRemote(gitRepository).getFirstUrl();

        WorkItemQueriesLookupOperation operation = new WorkItemQueriesLookupOperation(gitRemoteUrl);
        operation.addListener(new Operation.Listener() {
            @Override
            public void notifyLookupStarted() {
                // nothing to do
                logger.info("WorkItemQueriesLookupOperation started.");
            }

            @Override
            public void notifyLookupCompleted() {
                logger.info("WorkItemQueriesLookupOperation completed.");
                IdeaHelper.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        group.remove(loadingAction);
                    }
                });
            }

            @Override
            public void notifyLookupResults(final Operation.Results results) {
                final WorkItemQueriesLookupOperation.QueryResults wiResults = (WorkItemQueriesLookupOperation.QueryResults) results;

                if (wiResults.isCancelled()) {
                    // Do nothing
                    logger.info("WorkItemQueriesLookupOperation was cancelled");
                } else {
                    final ServerContext newContext;
                    if (wiResults.hasError() && AuthHelper.isNotAuthorizedError(wiResults.getError())) {
                        //401 or 403 - token is not valid, prompt user for credentials and retry
                        newContext = ServerContextManager.getInstance().updateAuthenticationInfo(gitRemoteUrl); //call this on a background thread, will hang UI thread if not
                    } else {
                        newContext = null;
                    }
                    // Update table model on UI thread
                    IdeaHelper.runOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            if (wiResults.hasError()) {
                                if (AuthHelper.isNotAuthorizedError(wiResults.getError())) {
                                    if (newContext != null) {
                                        //retry loading workitems with new context and authentication info
                                        addQueriesFromServer(group);
                                    } else {
                                        //user cancelled login, don't retry
                                        logger.info("WorkItemQueriesLookupOperation was cancelled");
                                    }
                                } else {
                                    IdeaHelper.showErrorDialog(gitRepository.getProject(), wiResults.getError());
                                }
                            }

                            // add results to the menu
                            for (final QueryHierarchyItem item : wiResults.getQueries()) {
                                //TODO check for folder items here and handle appropriately
                                group.add(new QueryAction(item.getName(), item.getWiql()));
                            }
                        }
                    });
                }
            }
        });
        operation.doWorkAsync(queryOperationInput);
    }

    protected class QueryAction extends DumbAwareAction {
        private final String queryName;
        private final String wiql;

        public QueryAction(final String queryName, final String wiql) {
            super(queryName);
            this.queryName = queryName;
            this.wiql = wiql;
            this.getTemplatePresentation().setText(queryName, false);
        }

        @Override
        public void actionPerformed(final AnActionEvent anActionEvent) {
            if (!this.equals(selectedQuery)) {
                // when new query is selected, the label needs to be updated and the listener has to be made aware of the action
                selectedQuery = this;
                pickerLabel.revalidate();
                pickerLabel.repaint();

                if (listener != null) {
                    listener.actionPerformed(new ActionEvent(this, 0, CMD_QUERY_COMBO_BOX_CHANGED));
                }
            }
        }
    }
}