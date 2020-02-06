package hudson.plugins.jira;

import com.atlassian.jira.rest.client.api.domain.Issue;
import com.atlassian.jira.rest.client.api.domain.Version;
import com.google.common.base.Splitter;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.jira.extension.TestExecution;
import hudson.plugins.jira.extension.TestRun;
import hudson.plugins.jira.extension.TestRun.ExecutionStatus;
import hudson.plugins.jira.selector.AbstractIssueSelector;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.JUnitParser;
import hudson.tasks.junit.SuiteResult;
import hudson.tasks.junit.TestResult;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import java.util.stream.Collectors;
/**
 * @author Merav Yaakov
 */

public class JiraTestCreator extends Recorder implements SimpleBuildStep {

    private static final Logger LOGGER = Logger.getLogger(JiraTestCreator.class.getName());



    private static final Logger LOG = Logger.getLogger(JiraTestCreator.class.getName());
    //private static final String XRAY_DATA_FOLDER = "\\\\amat.com\\folders\\Israel\\PDC_AutoTest\\TestsInputs\\Common\\Xray_Data";
    private static final String XRAY_DATA_FOLDER = "C:\\temp\\";
    private static PrintStream stdout = System.out;
    private Boolean report;
    private String branchName;
    private String testResults;
    private String projectKey;
    private String projectKeyUser;
    private String subProjectKey;
    private String fixVersion;
    //private String testDescription;
    private String assignee;
    private String component;
    //private Long typeId;
    //private Long priorityId;
    //private String steps;
    //private String linkIssue;
    //private Integer actionIdOnSuccess;

    @DataBoundConstructor
    public JiraTestCreator(String testResults,Boolean report,String projectKey,String assignee,String branchName, String component) {
        // if (projectKey == null) throw new IllegalArgumentException("Project key cannot be null");
        this.testResults = "";
        this.branchName=branchName;
        this.report=report;
        this.projectKey = "";
        this.projectKeyUser=projectKey;
        //this.testDescription = testDescription;
        this.assignee = assignee;
        this.component = "";
        //this.priorityId = priorityId;
        //this.actionIdOnSuccess = actionIdOnSuccess;
        //this.steps = steps;
        //this.linkIssue = linkIssue;
    }

    @Override
    public void perform(Run<?, ?> build, FilePath workspace, Launcher launcher,
                        TaskListener listener) throws InterruptedException, IOException {
        if (report) {
            final String expandedTestResults = build.getEnvironment(listener).expand(getTestResults());
            try {
                AbstractBuild<?, ?> abstractBuild = (AbstractBuild<?, ?>)build;

                TestResult testResult = new JUnitParser(false)
                    .parseResult(expandedTestResults, build, workspace, launcher, listener);
                JiraSession session = getJiraSession(abstractBuild);
                String jobDirPath = abstractBuild.getProject().getBuildDir().getPath();

                String filename = jobDirPath + File.separator + "issue.txt";
                PrintStream logger = listener.getLogger();
                for (SuiteResult testSuite : testResult.getSuites()) {
                    for (CaseResult caseResult : testSuite.getCases()) {
                        String summary = caseResult.getName();
                        String description = "";

                        setFixVersion(abstractBuild);
                        if (fixVersion.equals("empty")) {
                            EnvVars vars = build.getEnvironment(TaskListener.NULL);
                            logger.println("ERROR: Could not find Fix version: " + "'" + getBranchName(vars) + "'" +
                                           " test issue was not created for: " + caseResult.getName());
                            break;
                        }
                        if (projectKeyUser.isEmpty()){
                            projectKey = getProjectKey(caseResult.getClassName(), listener);
                        }else{
                            projectKey = getProjectKey(projectKeyUser,listener);
                        }

                        if (projectKey.equals("empty")) {
                            logger.println(
                                "ERROR: Could not find project key: " + "'" + caseResult.getClassName() + "'" +
                                " test issue was not created for: " + caseResult.getName());
                            break;
                        }
                        subProjectKey = getSubProjectKey(caseResult.getClassName(), listener);
                        if (subProjectKey.equals("empty")) {
                            logger.println(
                                "ERROR: Could not find sub project key for: " + "'" + caseResult.getClassName() + "'" +
                                " test issue was not created for: " + caseResult.getName());
                            break;
                        }

                        ExecutionStatus executionStatus = ExecutionStatus.PASS;
                        if (caseResult.isFailed() || caseResult.isSkipped()) {
                            executionStatus = ExecutionStatus.FAIL;
                            description = caseResult.getErrorDetails() + "\n" +
                                          caseResult.getErrorStackTrace();
                        }

                        Issue issue =
                            createJiraTest(abstractBuild, filename, summary, executionStatus, description, listener,
                                           fixVersion);

                    }

                }
            } catch (IOException e) {
                LOG.warning("Error creating JIRA issue\n" + e.getMessage());
            }
        }
    }

    public String getProjectKey(String project,TaskListener listener ) throws IOException {
        Reader csvData = Files.newBufferedReader(Paths.get(XRAY_DATA_FOLDER + "\\versionMapping.csv"));
        CSVParser parser = CSVParser.parse(csvData, CSVFormat.EXCEL);
        projectKey= project.substring(project.indexOf("#") + 1);
        List<CSVRecord> records = parser.getRecords().stream().filter(csvRecord -> csvRecord.get(0).equalsIgnoreCase(projectKey)).collect(
            Collectors.toList());

        if (records.size()!=0) {
            projectKey = records.get(0).get(1);
        }else {
            projectKey="empty";
        }
        return projectKey;
    }

    public String getSubProjectKey(String project,TaskListener listener) throws IOException {
        Reader csvData = Files.newBufferedReader(Paths.get(XRAY_DATA_FOLDER + "\\versionMapping.csv"));
        CSVParser parser = CSVParser.parse(csvData, CSVFormat.EXCEL);
        subProjectKey= project.substring(project.indexOf("#") + 1);
        List<CSVRecord> records = parser.getRecords().stream().filter(csvRecord -> csvRecord.get(0).equalsIgnoreCase(subProjectKey)).collect(
            Collectors.toList());
        if (records.size()!=0) {
            subProjectKey = records.get(0).get(2);
        }else {
            subProjectKey="empty";
        }
        return subProjectKey;
    }

    public String getTestResults() {
        return testResults;
    }

    public void setTestResults(String testResults) {
        this.testResults = testResults;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public void setProjectKey(String projectKey) {
        this.projectKey = projectKey;
    }

    //public String getTestDescription() {
    //    return testDescription;
    //}
    //
    //public void setTestDescription(String testDescription) {
    //    this.testDescription = testDescription;
    //}

    public String getAssignee() {
        return assignee;
    }

    public void setAssignee(String assignee) {
        this.assignee = assignee;
    }

    public String getComponent() {
        return component;
    }

    public void setComponent(String component) {
        this.component = component;
    }

    //public String getSteps() {
    //    return steps;
    //}
    //
    //public void setSteps(String steps) {
    //    this.steps = steps;
    //}

    //public String getLinkIssue() {
    //    return linkIssue;
    //}
    //
    //public void setLinkIssue(String linkIssue) {
    //    this.linkIssue = linkIssue;
    //}

    //public Long getTypeId() {
    //    return typeId;
    //}
    //
    //public Long getPriorityId() {
    //    return priorityId;
    //}

    //public Integer getActionIdOnSuccess() {
    //    return actionIdOnSuccess;
    //}

    /**
     * Returns the jira session.
     *
     * @param build
     * @return JiraSession
     * @throws IOException
     */
    private JiraSession getJiraSession(AbstractBuild<?, ?> build) {
        JiraSite site = getSiteForProject(build.getProject());

        if (site == null) {
            throw new IllegalStateException("JIRA site needs to be configured in the project " + build.getFullDisplayName());
        }

        JiraSession session = site.getSession();
        if (session == null) {
            throw new IllegalStateException("Remote access for JIRA isn't configured in Jenkins");
        }

        return session;
    }

    JiraSite getSiteForProject(AbstractProject<?, ?> project) {
        return JiraSite.get(project);
    }

    public void setFixVersion(AbstractBuild<?, ?> build) throws IOException, InterruptedException {
        EnvVars vars = build.getEnvironment(TaskListener.NULL);
        Reader csvData = Files.newBufferedReader(Paths.get(XRAY_DATA_FOLDER + "\\versionMapping.csv"));
        CSVParser parser = CSVParser.parse(csvData, CSVFormat.EXCEL);
        //String branchName = getBranchName(vars);
        List<CSVRecord> records = parser.getRecords().stream().filter(csvRecord -> csvRecord.get(0).equalsIgnoreCase(branchName)).collect(
            Collectors.toList());
        if (records.size()!=0) {
            fixVersion = records.get(0).get(1);
        }else {
            fixVersion="empty";
        }
    }

    private Issue createJiraTest(AbstractBuild<?, ?> build, String filename, String summary, ExecutionStatus executionStatus,
                                 @Nullable String description,TaskListener listener,String fixVersion) throws IOException, InterruptedException {
        PrintStream logger=listener.getLogger();
        EnvVars vars = build.getEnvironment(TaskListener.NULL);
        JiraSession session = getJiraSession(build);

        String buildName = getBuildName(vars);
        //String summary = String.format("Build %s failed", buildName);
        //String description = String.format(
        //    "%s\n\nThe build %s has failed.\nFirst failed run: %s",
        //    (this.testDescription.equals("")) ? "No description is provided" : this.testDescription,
        //    buildName,
        //    getBuildDetailsString(vars)
        //);
        Iterable<String> components = Splitter.on(",").trimResults().omitEmptyStrings().split(component);

        //Long type = typeId;
        //if (type == null || type == 0) { // zero is default / invalid selection
        //    LOG.info("Returning default issue type id " + BUG_ISSUE_TYPE_ID);
        //    type = BUG_ISSUE_TYPE_ID;
        //}
        //Long priority = priorityId;
        //if (priority != null && priority == 0) {
        //    priority = null; // remove invalid priority selection
        //}

        Issue issue = null;


        try {
            List<Issue> issues = session.getIssuesFromJqlSearch
                (String.format("project = \"%s\" and cf[16201] ~ \"%s\" and  issuetype= Test", projectKey, summary));
            /////////////////////////////
            String query=String.format("project = \"%s\" and cf[16201] ~ \"%s\" and  issuetype= Test", projectKey, summary);
            logger.println("Searching for Test issue with signatue: "+ query);





            ////////////////////////////
            String executionSummary = "Automated execution - build #" + buildName;
            if(issues.isEmpty()) {
                issue = session.createTestIssue(projectKey,subProjectKey, assignee, components, summary,listener);
                if (issue!=null) {
                    logger.println(String.format("Test issue [%s] created.", issue.getKey()));
                    TestExecution testExecution = session.createTestExecution(issue,
                                                                              executionSummary,
                                                                              executionStatus,
                                                                              description, fixVersion, listener);
                    logger.println(String.format("Test execution [%s] created.", testExecution.getTests().get(0)));
                    writeInFile(filename, issue);
                }
                else{ logger.println("ERROR: Could not create Test issue for: "+ summary); }
                return issue;
            } else {
                issue = session.getIssue(issues.get(0).getKey());
                //List<Issue> testExecutions = session.getIssuesFromJqlSearch
                //    (String.format("project = \"%s\" and issuetype in (11102, 11105)", projectKey, issue.getKey()));
                TestRun testRun = session.getTestRun(issue);
                if (testRun != null && !testRun.getTestExecutions().isEmpty()) {
                    String testExecutionKey = "";
                    for (TestRun.TestExecution testExecution : testRun.getTestExecutions()) { Issue testExecIssue = session.getIssue(testExecution.getKey());
                        for (Version version : Objects.requireNonNull(testExecIssue.getFixVersions())) {
                            if (version.getName().equals(fixVersion)) {
                                testExecutionKey = testExecution.getKey();
                            }
                        }
                    }

                    if (!testExecutionKey.isEmpty()) {
                        session.updateTestExecution(issue, testRun.getTestExecutions().get(0).getKey(),
                                                    executionSummary, executionStatus, description, fixVersion,listener);
                        logger.println(String.format("Status of test execution [%s] of test [%s] was updated.",
                                                     testRun.getTestExecutions().get(0).getKey(), issue.getKey()));
                        return issue;
                    }
                }
                TestExecution testExecution = session.createTestExecution(issue,
                                                                          "Automated execution - build #" +
                                                                          buildName,
                                                                          executionStatus,
                                                                          description, fixVersion, listener);
                logger.println(String.format("Status of test execution [%s] of test [%s] was updated.",
                                             testExecution.getTests().get(0), issue.getKey()));
                return issue;
            }
        } catch (TimeoutException e) {
            //

        }

        return issue;
    }

    private String getBuildName(EnvVars vars) {
        final String jobName = vars.get("JOB_NAME");
        final String buildNumber = vars.get("BUILD_NUMBER");
        return String.format("%s #%s", jobName, buildNumber);
    }

    private String getBranchName(EnvVars vars) {
        String branchName = vars.get("BRANCH_NAME");
        return branchName.substring(branchName.indexOf("#") + 1);
    }

    private String getToolName(EnvVars vars) {
        return vars.get("TOOL_NAME");
    }

    /**
     * Returns build details string in wiki format, with hyperlinks.
     *
     * @param vars
     * @return build details
     */
    private String getBuildDetailsString(EnvVars vars) {
        final String buildURL = vars.get("BUILD_URL");
        return String.format("[%s|%s] [console log|%s]", getBuildName(vars), buildURL, buildURL.concat("console"));
    }

    /**
     * write's the issue id in the file, which is stored in the Job's directory
     *
     * @param filename
     * @param issue
     * @throws FileNotFoundException
     */
    private void writeInFile(String filename, Issue issue) throws IOException {
        // olamy really weird to write an empty file especially with null
        // but backward compat and unit tests assert that.....
        // can't believe such stuff has been merged......
        try(BufferedWriter bw = Files.newBufferedWriter(Paths.get(filename ) )) {
            bw.write(issue.getKey()==null?"null":issue.getKey());
        }
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();


    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        private DescriptorImpl() {
            super(JiraTestCreator.class);
        }

        @Override
        public String getDisplayName() {
            // Displayed in the publisher section
            return Messages.JiraTestCreator_DisplayName();

        }

        @Override
        public String getHelpFile() {
            return "/plugin/jira/help-jira-create-tests.html";
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        public boolean hasIssueSelectors() {
            return Jenkins.getInstance().getDescriptorList(AbstractIssueSelector.class).size() > 1;
        }
    }

}
