package user.batch.create.portlet;

import com.liferay.petra.function.UnsafeRunnable;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.Company;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.scheduler.SchedulerJobConfiguration;
import com.liferay.portal.kernel.scheduler.TimeUnit;
import com.liferay.portal.kernel.scheduler.TriggerConfiguration;
import com.liferay.portal.kernel.service.CompanyLocalService;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.service.UserLocalService;
import com.liferay.portal.kernel.util.PortalClassLoaderUtil;

import java.util.Locale;
import java.util.regex.Pattern;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameClassPair;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.rowset.SqlRowSet;

@Component(
    immediate = true,
    service = SchedulerJobConfiguration.class
)
public class UserBatchCreateSchedulerJobConfiguration implements SchedulerJobConfiguration {

	private static DataSource bannerDataSource;

    private static long companyId;
    private static final Log LOG = LogFactoryUtil.getLog(UserBatchCreateSchedulerJobConfiguration.class);

   // @Reference
  //  private UserBatchCreateConfiguration _schedulerConfiguration = new UserBatchCreateConfigurationImpl();

    @Reference
    private CompanyLocalService companyLocalService;

    @Reference
    private UserLocalService userLocalService;

    @Activate
    protected void activate() {
        // 1) run once on startup
        try {
            LOG.info("Startup: running batch user‐create job immediately");
            getJobExecutorUnsafeRunnable().run();
        }
        catch (Exception e) {
            LOG.error("Error running startup batch job", e);
        }
    }
    
    /*
    private void initializeDataSource() {
        if (bannerDataSource == null) {
            synchronized (UserBatchCreateSchedulerJobConfiguration.class) {
                if (bannerDataSource == null) {
                    try {
                        //Context env = (Context) new InitialContext().lookup("java:comp/env");
                    	Context env = (Context) new InitialContext();
                        bannerDataSource = (DataSource) env.lookup("jdbc/shimPooledDB");
                        
                        Company company = null;
                        try {
                            company = companyLocalService.getCompanyByWebId("texastech.edu");
                            if (company != null) {
                                companyId = company.getCompanyId();
                            } else {
                                companyId = 20157; // Default fallback
                            }
                        } catch (PortalException | SystemException e) {
                            LOG.error("Error searching for company id" + "\n" + e);
                            companyId = 20157; // Default fallback
                        }
                    } catch (NamingException e) {
                        LOG.error("Failed to lookup data source", e);
                        throw new RuntimeException("Failed to lookup data source", e);
                    }
                }
            }
        }
    }*/
    
    private void initializeDataSource() {
        if (bannerDataSource != null) {
            return;
        }

        synchronized (UserBatchCreateSchedulerJobConfiguration.class) {
            if (bannerDataSource != null) {
                return;
            }

            
          
            
            
            ClassLoader originalCL = Thread.currentThread().getContextClassLoader();

            try {
                // Use portal classloader so JNDI sees Tomcat ResourceLinks
            	
            	// get the shielded class loader
                ClassLoader shieldedClassLoader = PortalClassLoaderUtil.getClassLoader();

                // get the webapp class loader from it
                ClassLoader webappClassLoader = shieldedClassLoader.getClass().getClassLoader();
             
            	
                Thread.currentThread().setContextClassLoader(
                		webappClassLoader
                );

                Context ctx = new InitialContext();

                try {
                    bannerDataSource = (DataSource)
                        ctx.lookup("java:comp/env/jdbc/shimPooledDB");
                    LOG.info("DataSource bound from java:comp/env/jdbc/shimPooledDB");
                }
                catch (NamingException ne) {
                    LOG.warn("java:comp/env lookup failed, trying root name", ne);
                    bannerDataSource = (DataSource)
                        ctx.lookup("jdbc/shimPooledDB");
                    LOG.info("DataSource bound from jdbc/shimPooledDB");
                }

                // Initialize companyId once
                try {
                    Company company =
                        companyLocalService.getCompanyByWebId("texastech.edu");

                    companyId = (company != null)
                        ? company.getCompanyId()
                        : 20157L;
                }
                catch (PortalException | SystemException e) {
                    LOG.error("Unable to resolve companyId, using fallback", e);
                    companyId = 20157L;
                }
            }
            catch (NamingException ne) {
                LOG.error("Failed to lookup DataSource", ne);
                throw new RuntimeException("DataSource JNDI lookup failed", ne);
            }
            finally {
                Thread.currentThread().setContextClassLoader(originalCL);
            }
        }
    }


    
    
    @Override
    public UnsafeRunnable<Exception> getJobExecutorUnsafeRunnable() {
    	System.out.println("STARTING BATACH USER CREATE");
        return () -> {
            LOG.info("Running user batch create job");
            System.out.println("Running user batch create job");

            initializeDataSource();
            
            JdbcTemplate jt = new JdbcTemplate(bannerDataSource);

            String sql = "SELECT CAS_BANNER_EMAIL FROM CAS_BANNER " +
                " WHERE ( " +
                "    TRUNC(CAS_BANNER_INSERT_DATE) BETWEEN TRUNC(SYSDATE) - 2 AND TRUNC(SYSDATE) " +
                "    OR " +
                "    TRUNC(CAS_BANNER_ACTIVITY_DATE) BETWEEN TRUNC(SYSDATE) - 2 AND TRUNC(SYSDATE) " +
                " ) " +
                " AND CAS_BANNER_EMAIL IS NOT NULL " +
                " AND LENGTH(TRIM(CAS_BANNER_EMAIL)) > 0";

            SqlRowSet newUsersToday = jt.queryForRowSet(sql);

            int index =0;
            while (newUsersToday.next()) {
            	index++;
            	if(index>3) break;
                String newUserTodayEmail = newUsersToday.getString("CAS_BANNER_EMAIL");
                
                if (newUserTodayEmail == null || newUserTodayEmail.trim().isEmpty()) {
                    LOG.warn("Skipping row with null or empty CAS_BANNER_EMAIL");
                    continue;
                }
                
                newUserTodayEmail = newUserTodayEmail.trim();
                
                if (!isValidEmail(newUserTodayEmail)) {
                    LOG.warn("Skipping row with invalid email address:" + newUserTodayEmail);
                    System.out.println("Skipping row with invalid email address:" + newUserTodayEmail);
                    continue;
                }

                User user = null;
                boolean userCreated = false;

                try {
                    user = userLocalService.fetchUserByEmailAddress(companyId, newUserTodayEmail);
                } catch (SystemException e) {
                    LOG.error("Error searching for user " + newUserTodayEmail + "\n" + e);
                    System.out.println("Error searching for user " + newUserTodayEmail + "\n" + e);
                }
                
                if (user == null) {
                    try {
                        user = createUser(companyId, newUserTodayEmail);
                        userCreated = true;
                    } catch (PortalException | SystemException e) {
                        LOG.error("Error creating user " + newUserTodayEmail + "\n" + e);
                        System.out.println("Error creating user " + newUserTodayEmail + "\n" + e);
                    }
                    
                    if (userCreated) {
                        try {
                            Thread.sleep(500); // Sleep for 0.5 second
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt(); // Best practice
                            LOG.warn("Sleep interrupted", e);
                        }
                    }

                    LOG.info("Auto-created user: " + newUserTodayEmail + " (companyId: " + companyId + ")");
                    System.out.println("Auto-created user: " + newUserTodayEmail + " (companyId: " + companyId + ")");
                }
            }
        };
    }


    @Override
    public TriggerConfiguration getTriggerConfiguration() {
        // Hardcoded interval (60 minutes)
        return TriggerConfiguration.createTriggerConfiguration(60, TimeUnit.MINUTE);
    }

    
    private User createUser(long companyId, String emailAddress)
            throws PortalException, SystemException {

        long creatorUserId = 0; // Default user (omnibus user)
        boolean autoPassword = true;
        String password1 = "";
        String password2 = "";
        boolean autoScreenName = true;
        String screenName = "";
        String firstName = "Default";
        String middleName = "";
        String lastName = "User";
        long prefixListTypeId = 0;
        long suffixListTypeId = 0;
        boolean male = true;
        int birthdayMonth = 1;
        int birthdayDay = 1;
        int birthdayYear = 1970;
        String jobTitle = "";
        int type = 1; // Default user type
        long[] groupIds = null;
        long[] organizationIds = null;
        long[] roleIds = null;
        long[] userGroupIds = null;
        boolean sendEmail = false;
        ServiceContext serviceContext = new ServiceContext();
        
        User user = userLocalService.addUser(
                creatorUserId,            // creatorUserId
                companyId,                // companyId
                autoPassword,             // autoPassword
                password1,                // password1
                password2,                // password2
                autoScreenName,           // autoScreenName
                screenName,               // screenName
                emailAddress,             // emailAddress
                Locale.US,                // locale
                firstName,                // firstName
                middleName,               // middleName
                lastName,                 // lastName
                prefixListTypeId,         // prefixListTypeId
                suffixListTypeId,         // suffixListTypeId
                male,                     // male
                birthdayMonth,            // birthdayMonth
                birthdayDay,              // birthdayDay
                birthdayYear,             // birthdayYear
                jobTitle,                 // jobTitle
                type,                     // type
                groupIds,                 // groupIds
                organizationIds,          // organizationIds
                roleIds,                  // roleIds
                userGroupIds,             // userGroupIds
                sendEmail,                // sendEmail
                serviceContext            // serviceContext
        );
                
        if (user == null) {
            LOG.error("addUser returned null for email" + emailAddress);
            System.out.println("addUser returned null for email" + emailAddress);
            return null;
        }
        
        // Set passwordReset to false
        user.setPasswordReset(false);
        // Set agreedToTermsOfUse to true to skip the User Agreement screen
        user.setAgreedToTermsOfUse(true);
        // Set reminderQuerySet to true to skip the password reminder setup
        user.setReminderQueryAnswer("not_aplicable");
        user.setReminderQueryQuestion("not_applicable");
        userLocalService.updateUser(user);
        
        LOG.info("User created " + user.getEmailAddress());
        System.out.println("User created " + user.getEmailAddress());
        return user;
    }
    
    private boolean isValidEmail(String email) {
        String regex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$";
        return Pattern.matches(regex, email);
    }
    
  
    
    
    
}