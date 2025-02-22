package teammates.e2e.cases;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import org.testng.ITestContext;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

import teammates.common.datatransfer.DataBundle;
import teammates.common.datatransfer.UserInfoCookie;
import teammates.common.datatransfer.attributes.AccountAttributes;
import teammates.common.datatransfer.attributes.CourseAttributes;
import teammates.common.datatransfer.attributes.FeedbackQuestionAttributes;
import teammates.common.datatransfer.attributes.FeedbackResponseAttributes;
import teammates.common.datatransfer.attributes.FeedbackResponseCommentAttributes;
import teammates.common.datatransfer.attributes.FeedbackSessionAttributes;
import teammates.common.datatransfer.attributes.InstructorAttributes;
import teammates.common.datatransfer.attributes.StudentAttributes;
import teammates.common.datatransfer.attributes.StudentProfileAttributes;
import teammates.common.exception.HttpRequestFailedException;
import teammates.common.exception.TeammatesException;
import teammates.common.util.AppUrl;
import teammates.common.util.Const;
import teammates.common.util.JsonUtils;
import teammates.common.util.StringHelper;
import teammates.common.util.ThreadHelper;
import teammates.e2e.pageobjects.AppPage;
import teammates.e2e.pageobjects.Browser;
import teammates.e2e.pageobjects.DevServerLoginPage;
import teammates.e2e.pageobjects.HomePage;
import teammates.e2e.util.BackDoor;
import teammates.e2e.util.EmailAccount;
import teammates.e2e.util.TestProperties;
import teammates.test.BaseTestCaseWithDatastoreAccess;
import teammates.test.FileHelper;

/**
 * Base class for all browser tests.
 *
 * <p>This type of test has no knowledge of the workings of the application,
 * and can only communicate via the UI or via {@link BackDoor} to obtain/transmit data.
 */
public abstract class BaseE2ETestCase extends BaseTestCaseWithDatastoreAccess {

    static final BackDoor BACKDOOR = BackDoor.getInstance();

    protected DataBundle testData;
    private Browser browser;

    @BeforeClass
    public void baseClassSetup() throws Exception {
        prepareTestData();
        prepareBrowser();
    }

    protected void prepareBrowser() {
        browser = new Browser();
    }

    protected abstract void prepareTestData() throws Exception;

    protected abstract void testAll();

    @Override
    protected String getTestDataFolder() {
        return TestProperties.TEST_DATA_FOLDER;
    }

    protected String getTestDownloadsFolder() {
        return TestProperties.TEST_DOWNLOADS_FOLDER;
    }

    @AfterClass
    public void baseClassTearDown(ITestContext context) {
        boolean isSuccess = context.getFailedTests().getAllMethods()
                .stream()
                .noneMatch(method -> method.getConstructorOrMethod().getMethod().getDeclaringClass() == this.getClass());
        releaseBrowser(isSuccess);
    }

    protected void releaseBrowser(boolean isSuccess) {
        if (browser == null) {
            return;
        }
        if (isSuccess || TestProperties.CLOSE_BROWSER_ON_FAILURE) {
            browser.close();
        }
    }

    /**
     * Creates an {@link AppUrl} for the supplied {@code relativeUrl} parameter.
     * The base URL will be the value of test.app.url in test.properties.
     * {@code relativeUrl} must start with a "/".
     */
    protected static AppUrl createUrl(String relativeUrl) {
        return new AppUrl(TestProperties.TEAMMATES_URL + relativeUrl);
    }

    /**
     * Logs in to a page using the given credentials.
     */
    protected <T extends AppPage> T loginToPage(AppUrl url, Class<T> typeOfPage, String userId) {
        // When not using dev server, Google blocks log in by automation.
        // To work around that, we inject the user cookie directly into the browser session.
        if (!TestProperties.isDevServer()) {
            // In order for the cookie injection to work, we need to be in the domain.
            // Use the home page to minimize the page load time.
            browser.goToUrl(TestProperties.TEAMMATES_URL);

            UserInfoCookie uic = new UserInfoCookie(userId);
            browser.addCookie(Const.SecurityConfig.AUTH_COOKIE_NAME, StringHelper.encrypt(JsonUtils.toCompactJson(uic)),
                    true, true);

            return getNewPageInstance(url, typeOfPage);
        }

        // This will be redirected to the dev server login page.
        browser.goToUrl(url.toAbsoluteString());

        DevServerLoginPage loginPage = AppPage.getNewPageInstance(browser, DevServerLoginPage.class);
        loginPage.loginAsUser(userId);

        return getNewPageInstance(url, typeOfPage);
    }

    /**
     * Logs in to a page using admin credentials.
     */
    protected <T extends AppPage> T loginAdminToPage(AppUrl url, Class<T> typeOfPage) {
        return loginToPage(url, typeOfPage, TestProperties.TEST_ADMIN);
    }

    /**
     * Equivalent to clicking the 'logout' link in the top menu of the page.
     */
    protected void logout() {
        browser.goToUrl(createUrl(Const.WebPageURIs.LOGOUT).toAbsoluteString());
        AppPage.getNewPageInstance(browser, HomePage.class).waitForPageToLoad();
    }

    /**
     * Deletes file with fileName from the downloads folder.
     */
    protected void deleteDownloadsFile(String fileName) {
        String filePath = getTestDownloadsFolder() + fileName;
        FileHelper.deleteFile(filePath);
    }

    /**
     * Verifies downloaded file has correct fileName and contains expected content.
     */
    protected void verifyDownloadedFile(String expectedFileName, List<String> expectedContent) {
        String filePath = getTestDownloadsFolder() + expectedFileName;
        int retryLimit = TestProperties.TEST_TIMEOUT;
        boolean actual = Files.exists(Paths.get(filePath));
        while (!actual && retryLimit > 0) {
            retryLimit--;
            ThreadHelper.waitFor(1000);
            actual = Files.exists(Paths.get(filePath));
        }
        assertTrue(actual);

        try {
            String actualContent = FileHelper.readFile(filePath);
            for (String content : expectedContent) {
                assertTrue(actualContent.contains(content));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected <T extends AppPage> T getNewPageInstance(AppUrl url, Class<T> typeOfPage) {
        browser.goToUrl(url.toAbsoluteString());
        return AppPage.getNewPageInstance(browser, typeOfPage);
    }

    /**
     * Verifies that email with subject is found in inbox.
     * Email used must be an authentic gmail account.
     */
    protected void verifyEmailSent(String email, String subject) {
        if (TestProperties.isDevServer() || !TestProperties.INCLUDE_EMAIL_VERIFICATION) {
            return;
        }
        if (!TestProperties.TEST_EMAIL.equals(email)) {
            fail("Email verification is allowed only on preset test email.");
        }
        EmailAccount emailAccount = new EmailAccount(email);
        try {
            emailAccount.getUserAuthenticated();
            int retryLimit = 5;
            boolean actual = emailAccount.isRecentEmailWithSubjectPresent(subject, TestProperties.TEST_SENDER_EMAIL);
            while (!actual && retryLimit > 0) {
                retryLimit--;
                ThreadHelper.waitFor(1000);
                actual = emailAccount.isRecentEmailWithSubjectPresent(subject, TestProperties.TEST_SENDER_EMAIL);
            }
            assertTrue(actual);
        } catch (Exception e) {
            fail("Failed to verify email sent:" + e);
        }
    }

    @Override
    @SuppressWarnings("PMD.EmptyMethodInAbstractClassShouldBeAbstract")
    public void setupLocalDatastoreHelper() {
        // Should be prepared separately
    }

    @Override
    @SuppressWarnings("PMD.EmptyMethodInAbstractClassShouldBeAbstract")
    public void resetLocalDatastoreHelper() {
        // Local datastore state should persist across e2e test suites
    }

    @Override
    @SuppressWarnings("PMD.EmptyMethodInAbstractClassShouldBeAbstract")
    public void tearDownLocalDatastoreHelper() {
        // Should be prepared separately
    }

    @Override
    @SuppressWarnings("PMD.EmptyMethodInAbstractClassShouldBeAbstract")
    public void setupSearch() {
        // Not necessary as BackDoor API is used instead
    }

    @Override
    @SuppressWarnings("PMD.EmptyMethodInAbstractClassShouldBeAbstract")
    public void resetSearchService() {
        // Not necessary as BackDoor API is used instead
    }

    @Override
    @SuppressWarnings("PMD.EmptyMethodInAbstractClassShouldBeAbstract")
    public void setupObjectify() {
        // Not necessary as BackDoor API is used instead
    }

    @Override
    @SuppressWarnings("PMD.EmptyMethodInAbstractClassShouldBeAbstract")
    public void tearDownObjectify() {
        // Not necessary as BackDoor API is used instead
    }

    protected AccountAttributes getAccount(String googleId) {
        return BACKDOOR.getAccount(googleId);
    }

    @Override
    protected AccountAttributes getAccount(AccountAttributes account) {
        return getAccount(account.googleId);
    }

    @Override
    @SuppressWarnings("PMD.EmptyMethodInAbstractClassShouldBeAbstract")
    protected StudentProfileAttributes getStudentProfile(StudentProfileAttributes studentProfileAttributes) {
        return null; // BACKDOOR.getStudentProfile(studentProfileAttributes.googleId);
    }

    protected CourseAttributes getCourse(String courseId) {
        return BACKDOOR.getCourse(courseId);
    }

    @Override
    protected CourseAttributes getCourse(CourseAttributes course) {
        return getCourse(course.getId());
    }

    protected CourseAttributes getArchivedCourse(String instructorId, String courseId) {
        return BACKDOOR.getArchivedCourse(instructorId, courseId);
    }

    protected FeedbackQuestionAttributes getFeedbackQuestion(String courseId, String feedbackSessionName, int qnNumber) {
        return BACKDOOR.getFeedbackQuestion(courseId, feedbackSessionName, qnNumber);
    }

    @Override
    protected FeedbackQuestionAttributes getFeedbackQuestion(FeedbackQuestionAttributes fq) {
        return getFeedbackQuestion(fq.courseId, fq.feedbackSessionName, fq.questionNumber);
    }

    protected FeedbackResponseCommentAttributes getFeedbackResponseComment(String feedbackResponseId) {
        return BACKDOOR.getFeedbackResponseComment(feedbackResponseId);
    }

    @Override
    protected FeedbackResponseCommentAttributes getFeedbackResponseComment(FeedbackResponseCommentAttributes frc) {
        return getFeedbackResponseComment(frc.feedbackResponseId);
    }

    protected FeedbackResponseAttributes getFeedbackResponse(String feedbackQuestionId, String giver, String recipient) {
        return BACKDOOR.getFeedbackResponse(feedbackQuestionId, giver, recipient);
    }

    @Override
    protected FeedbackResponseAttributes getFeedbackResponse(FeedbackResponseAttributes fr) {
        return getFeedbackResponse(fr.feedbackQuestionId, fr.giver, fr.recipient);
    }

    protected FeedbackSessionAttributes getFeedbackSession(String courseId, String feedbackSessionName) {
        return BACKDOOR.getFeedbackSession(courseId, feedbackSessionName);
    }

    @Override
    protected FeedbackSessionAttributes getFeedbackSession(FeedbackSessionAttributes fs) {
        return getFeedbackSession(fs.getCourseId(), fs.getFeedbackSessionName());
    }

    protected FeedbackSessionAttributes getSoftDeletedSession(String feedbackSessionName, String instructorId) {
        return BACKDOOR.getSoftDeletedSession(feedbackSessionName, instructorId);
    }

    protected InstructorAttributes getInstructor(String courseId, String instructorEmail) {
        return BACKDOOR.getInstructor(courseId, instructorEmail);
    }

    @Override
    protected InstructorAttributes getInstructor(InstructorAttributes instructor) {
        return getInstructor(instructor.courseId, instructor.email);
    }

    protected String getKeyForInstructor(String courseId, String instructorEmail) {
        return getInstructor(courseId, instructorEmail).getKey();
    }

    @Override
    protected StudentAttributes getStudent(StudentAttributes student) {
        return BACKDOOR.getStudent(student.course, student.email);
    }

    protected String getKeyForStudent(StudentAttributes student) {
        return getStudent(student).getKey();
    }

    @Override
    protected boolean doRemoveAndRestoreDataBundle(DataBundle testData) {
        try {
            BACKDOOR.removeAndRestoreDataBundle(testData);
            return true;
        } catch (HttpRequestFailedException e) {
            print(TeammatesException.toStringWithStackTrace(e));
            return false;
        }
    }

    @Override
    protected boolean doPutDocuments(DataBundle testData) {
        try {
            BACKDOOR.putDocuments(testData);
            return true;
        } catch (HttpRequestFailedException e) {
            print(TeammatesException.toStringWithStackTrace(e));
            return false;
        }
    }

}
