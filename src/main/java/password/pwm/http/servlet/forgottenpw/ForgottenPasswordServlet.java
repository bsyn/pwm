/*
 * Password Management Servlets (PWM)
 * http://www.pwm-project.org
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2016 The PWM Project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package password.pwm.http.servlet.forgottenpw;

import com.novell.ldapchai.ChaiUser;
import com.novell.ldapchai.cr.Challenge;
import com.novell.ldapchai.cr.ChallengeSet;
import com.novell.ldapchai.cr.ResponseSet;
import com.novell.ldapchai.exception.ChaiOperationException;
import com.novell.ldapchai.exception.ChaiUnavailableException;
import com.novell.ldapchai.exception.ChaiValidationException;
import com.novell.ldapchai.provider.ChaiProvider;
import password.pwm.AppProperty;
import password.pwm.PwmApplication;
import password.pwm.PwmConstants;
import password.pwm.VerificationMethodSystem;
import password.pwm.bean.EmailItemBean;
import password.pwm.bean.PasswordStatus;
import password.pwm.bean.SessionLabel;
import password.pwm.bean.UserIdentity;
import password.pwm.bean.UserInfoBean;
import password.pwm.config.ActionConfiguration;
import password.pwm.config.Configuration;
import password.pwm.config.FormConfiguration;
import password.pwm.config.FormUtility;
import password.pwm.config.PwmSetting;
import password.pwm.config.option.IdentityVerificationMethod;
import password.pwm.config.option.MessageSendMethod;
import password.pwm.config.option.RecoveryAction;
import password.pwm.config.profile.ForgottenPasswordProfile;
import password.pwm.config.profile.ProfileType;
import password.pwm.config.profile.ProfileUtility;
import password.pwm.error.ErrorInformation;
import password.pwm.error.PwmDataValidationException;
import password.pwm.error.PwmError;
import password.pwm.error.PwmException;
import password.pwm.error.PwmOperationalException;
import password.pwm.error.PwmUnrecoverableException;
import password.pwm.http.HttpMethod;
import password.pwm.http.PwmHttpRequestWrapper;
import password.pwm.http.PwmRequest;
import password.pwm.http.PwmSession;
import password.pwm.http.bean.ForgottenPasswordBean;
import password.pwm.http.filter.AuthenticationFilter;
import password.pwm.http.servlet.AbstractPwmServlet;
import password.pwm.http.servlet.PwmServletDefinition;
import password.pwm.http.servlet.oauth.OAuthForgottenPasswordResults;
import password.pwm.http.servlet.oauth.OAuthMachine;
import password.pwm.http.servlet.oauth.OAuthSettings;
import password.pwm.i18n.Message;
import password.pwm.ldap.LdapOperationsHelper;
import password.pwm.ldap.LdapUserDataReader;
import password.pwm.ldap.UserDataReader;
import password.pwm.ldap.UserSearchEngine;
import password.pwm.ldap.UserStatusReader;
import password.pwm.ldap.auth.AuthenticationType;
import password.pwm.ldap.auth.AuthenticationUtility;
import password.pwm.ldap.auth.PwmAuthenticationSource;
import password.pwm.ldap.auth.SessionAuthenticator;
import password.pwm.svc.event.AuditEvent;
import password.pwm.svc.intruder.RecordType;
import password.pwm.svc.stats.Statistic;
import password.pwm.svc.stats.StatisticsManager;
import password.pwm.svc.token.TokenPayload;
import password.pwm.svc.token.TokenService;
import password.pwm.svc.token.TokenType;
import password.pwm.util.Helper;
import password.pwm.util.JsonUtil;
import password.pwm.util.PasswordData;
import password.pwm.util.PostChangePasswordAction;
import password.pwm.util.RandomPasswordGenerator;
import password.pwm.util.logging.PwmLogger;
import password.pwm.util.macro.MacroMachine;
import password.pwm.util.operations.ActionExecutor;
import password.pwm.util.operations.PasswordUtility;
import password.pwm.util.operations.cr.NMASCrOperator;
import password.pwm.util.otp.OTPUserRecord;
import password.pwm.ws.client.rest.RestTokenDataClient;
import password.pwm.ws.client.rest.naaf.PwmNAAFVerificationMethod;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * User interaction servlet for recovering user's password using secret question/answer
 *
 * @author Jason D. Rivard
 */


@WebServlet(
        name="ForgottenPasswordServlet",
        urlPatterns = {
                PwmConstants.URL_PREFIX_PUBLIC + "/forgottenpassword",
                PwmConstants.URL_PREFIX_PUBLIC + "/forgottenpassword/*",
                PwmConstants.URL_PREFIX_PUBLIC + "/ForgottenPassword",
                PwmConstants.URL_PREFIX_PUBLIC + "/ForgottenPassword/*",
        }
)
public class ForgottenPasswordServlet extends AbstractPwmServlet {
// ------------------------------ FIELDS ------------------------------

    private static final PwmLogger LOGGER = PwmLogger.forClass(ForgottenPasswordServlet.class);

    public enum ForgottenPasswordAction implements AbstractPwmServlet.ProcessAction {
        search(HttpMethod.POST),
        checkResponses(HttpMethod.POST),
        checkAttributes(HttpMethod.POST),
        enterCode(HttpMethod.POST, HttpMethod.GET),
        enterOtp(HttpMethod.POST),
        reset(HttpMethod.POST),
        actionChoice(HttpMethod.POST),
        tokenChoice(HttpMethod.POST),
        verificationChoice(HttpMethod.POST),
        enterNaafResponse(HttpMethod.POST),
        enterRemoteResponse(HttpMethod.POST),
        oauthReturn(HttpMethod.GET),

        ;

        private final Collection<HttpMethod> method;

        ForgottenPasswordAction(final HttpMethod... method)
        {
            this.method = Collections.unmodifiableList(Arrays.asList(method));
        }

        public Collection<HttpMethod> permittedMethods()
        {
            return method;
        }
    }

    protected ForgottenPasswordAction readProcessAction(final PwmRequest request)
            throws PwmUnrecoverableException
    {
        try {
            return ForgottenPasswordAction.valueOf(request.readParameterAsString(PwmConstants.PARAM_ACTION_REQUEST));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }


    @Override
    public void processAction(final PwmRequest pwmRequest)
            throws ServletException, IOException, ChaiUnavailableException, PwmUnrecoverableException
    {
        final PwmSession pwmSession = pwmRequest.getPwmSession();
        final PwmApplication pwmApplication = pwmRequest.getPwmApplication();

        final Configuration config = pwmApplication.getConfig();
        final ForgottenPasswordBean forgottenPasswordBean = forgottenPasswordBean(pwmRequest);

        if (!config.readSettingAsBoolean(PwmSetting.FORGOTTEN_PASSWORD_ENABLE)) {
            pwmRequest.respondWithError(PwmError.ERROR_SERVICE_NOT_AVAILABLE.toInfo());
            return;
        }

        if (pwmSession.isAuthenticated()) {
            pwmRequest.respondWithError(PwmError.ERROR_USERAUTHENTICATED.toInfo());
            return;
        }

        if (forgottenPasswordBean.getUserIdentity() != null) {
            pwmApplication.getIntruderManager().convenience().checkUserIdentity(forgottenPasswordBean.getUserIdentity());
        }

        checkForLocaleSwitch(pwmRequest, forgottenPasswordBean);

        final ForgottenPasswordAction processAction = readProcessAction(pwmRequest);

        // convert a url command like /pwm/public/ForgottenPassword/12321321 to redirect with a process action.
        if (processAction == null) {
            if (pwmRequest.convertURLtokenCommand()) {
                return;
            }
        }

        if (processAction != null) {

            switch (processAction) {
                case search:
                    this.processSearch(pwmRequest);
                    break;

                case checkResponses:
                    this.processCheckResponses(pwmRequest);
                    break;

                case checkAttributes:
                    this.processCheckAttributes(pwmRequest);
                    break;

                case enterCode:
                    this.processEnterToken(pwmRequest);
                    break;

                case enterOtp:
                    this.processEnterOtpToken(pwmRequest);
                    break;

                case reset:
                    this.processReset(pwmRequest);
                    break;

                case actionChoice:
                    this.processActionChoice(pwmRequest);
                    break;

                case tokenChoice:
                    this.processTokenChoice(pwmRequest);
                    break;

                case verificationChoice:
                    this.processVerificationChoice(pwmRequest);
                    break;

                case enterNaafResponse:
                    this.processEnterNaaf(pwmRequest);
                    break;

                case enterRemoteResponse:
                    this.processEnterRemote(pwmRequest);
                    break;

                case oauthReturn:
                    this.processOAuthReturn(pwmRequest);
                    break;

                default:
                    Helper.unhandledSwitchStatement(processAction);
            }
        } else {
            pwmApplication.getSessionStateService().clearBean(pwmRequest, ForgottenPasswordBean.class);
        }

        if (!pwmRequest.getPwmResponse().isCommitted()) {
            this.advancedToNextStage(pwmRequest);
        }
    }

    private ForgottenPasswordBean forgottenPasswordBean(final PwmRequest pwmRequest) throws PwmUnrecoverableException {
        return pwmRequest.getPwmApplication().getSessionStateService().getBean(pwmRequest, ForgottenPasswordBean.class);
    }

    private void clearForgottenPasswordBean(final PwmRequest pwmRequest) throws PwmUnrecoverableException {
        pwmRequest.getPwmApplication().getSessionStateService().clearBean(pwmRequest, ForgottenPasswordBean.class);
    }

    private void processActionChoice(final PwmRequest pwmRequest)
            throws PwmUnrecoverableException, ServletException, IOException, ChaiUnavailableException
    {
        final ForgottenPasswordBean forgottenPasswordBean = forgottenPasswordBean(pwmRequest);

        if (forgottenPasswordBean.getProgress().isAllPassed()) {
            final String choice = pwmRequest.readParameterAsString("choice");
            if (choice != null) {
                if ("unlock".equals(choice)) {
                    this.executeUnlock(pwmRequest);
                } else if ("resetPassword".equalsIgnoreCase(choice)) {
                    this.executeResetPassword(pwmRequest);
                }
            }
        }
    }

    private void processReset(final PwmRequest pwmRequest)
            throws IOException, PwmUnrecoverableException
    {
        final ForgottenPasswordBean forgottenPasswordBean = forgottenPasswordBean(pwmRequest);

        clearForgottenPasswordBean(pwmRequest);

        if (forgottenPasswordBean.getUserIdentity() == null) {
            pwmRequest.sendRedirectToContinue();
        }
    }

    private void processTokenChoice(final PwmRequest pwmRequest)
            throws PwmUnrecoverableException, ServletException, IOException, ChaiUnavailableException
    {
        final ForgottenPasswordBean forgottenPasswordBean = forgottenPasswordBean(pwmRequest);
        if (forgottenPasswordBean.getProgress().getTokenSendChoice() == MessageSendMethod.CHOICE_SMS_EMAIL) {
            final String choice = pwmRequest.readParameterAsString("choice");
            if (choice != null) {
                if ("email".equals(choice)) {
                    forgottenPasswordBean.getProgress().setTokenSendChoice(MessageSendMethod.EMAILONLY);
                } else if ("sms".equalsIgnoreCase(choice)) {
                    forgottenPasswordBean.getProgress().setTokenSendChoice(MessageSendMethod.SMSONLY);
                }
            }
        }
    }

    private void processVerificationChoice(final PwmRequest pwmRequest)
            throws PwmUnrecoverableException, ServletException, IOException
    {
        final ForgottenPasswordBean forgottenPasswordBean = forgottenPasswordBean(pwmRequest);
        final String requestedChoiceStr = pwmRequest.readParameterAsString("choice");
        final LinkedHashSet<IdentityVerificationMethod> remainingAvailableOptionalMethods = new LinkedHashSet<>(
                figureRemainingAvailableOptionalAuthMethods(pwmRequest, forgottenPasswordBean)
        );
        pwmRequest.setAttribute(PwmRequest.Attribute.AvailableAuthMethods, remainingAvailableOptionalMethods);

        IdentityVerificationMethod requestedChoice = null;
        if (requestedChoiceStr != null && !requestedChoiceStr.isEmpty()) {
            try {
                requestedChoice = IdentityVerificationMethod.valueOf(requestedChoiceStr);
            } catch (IllegalArgumentException e) {
                final String errorMsg = "unknown verification method requested";
                final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_MISSING_PARAMETER,errorMsg);
                pwmRequest.setResponseError(errorInformation);
                pwmRequest.forwardToJsp(PwmConstants.JspUrl.RECOVER_PASSWORD_METHOD_CHOICE);
                return;
            }
        }

        if (remainingAvailableOptionalMethods.contains(requestedChoice)) {
            forgottenPasswordBean.getProgress().setInProgressVerificationMethod(requestedChoice);
            pwmRequest.setAttribute(PwmRequest.Attribute.ForgottenPasswordOptionalPageView,"true");
            forwardUserBasedOnRecoveryMethod(pwmRequest, requestedChoice);
            return;
        } else if (requestedChoice != null) {
            final String errorMsg = "requested verification method is not available at this time";
            final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_MISSING_PARAMETER,errorMsg);
            pwmRequest.setResponseError(errorInformation);
        }

        pwmRequest.forwardToJsp(PwmConstants.JspUrl.RECOVER_PASSWORD_METHOD_CHOICE);
    }

    private void processSearch(final PwmRequest pwmRequest)
            throws ChaiUnavailableException, PwmUnrecoverableException, IOException, ServletException
    {
        final PwmSession pwmSession = pwmRequest.getPwmSession();
        final Locale userLocale = pwmRequest.getLocale();
        final PwmApplication pwmApplication = pwmRequest.getPwmApplication();

        final String contextParam = pwmRequest.readParameterAsString(PwmConstants.PARAM_CONTEXT);
        final String ldapProfile = pwmRequest.readParameterAsString(PwmConstants.PARAM_LDAP_PROFILE);

        // clear the bean
        clearForgottenPasswordBean(pwmRequest);

        final List<FormConfiguration> forgottenPasswordForm = pwmApplication.getConfig().readSettingAsForm(
                PwmSetting.FORGOTTEN_PASSWORD_SEARCH_FORM);

        Map<FormConfiguration, String> formValues = new HashMap<>();

        try {
            //read the values from the request
            formValues = FormUtility.readFormValuesFromRequest(pwmRequest, forgottenPasswordForm, userLocale);

            // check for intruder search values
            pwmApplication.getIntruderManager().convenience().checkAttributes(formValues);

            // see if the values meet the configured form requirements.
            FormUtility.validateFormValues(pwmRequest.getConfig(), formValues, userLocale);

            final String searchFilter;
            {
                final String configuredSearchFilter = pwmApplication.getConfig().readSettingAsString(PwmSetting.FORGOTTEN_PASSWORD_SEARCH_FILTER);
                if (configuredSearchFilter == null || configuredSearchFilter.isEmpty()) {
                    searchFilter = FormUtility.ldapSearchFilterForForm(pwmApplication, forgottenPasswordForm);
                    LOGGER.trace(pwmSession,"auto generated ldap search filter: " + searchFilter);
                } else {
                    searchFilter = configuredSearchFilter;
                }
            }

            // convert the username field to an identity
            final UserIdentity userIdentity;
            {
                final UserSearchEngine userSearchEngine = new UserSearchEngine(pwmRequest);
                final UserSearchEngine.SearchConfiguration searchConfiguration = new UserSearchEngine.SearchConfiguration();
                searchConfiguration.setFilter(searchFilter);
                searchConfiguration.setFormValues(formValues);
                searchConfiguration.setContexts(Collections.singletonList(contextParam));
                searchConfiguration.setLdapProfile(ldapProfile);
                userIdentity = userSearchEngine.performSingleUserSearch(searchConfiguration);
            }

            if (userIdentity == null) {
                pwmApplication.getIntruderManager().convenience().markAddressAndSession(pwmSession);
                pwmApplication.getStatisticsManager().incrementValue(Statistic.RECOVERY_FAILURES);
                pwmRequest.setResponseError(PwmError.ERROR_CANT_MATCH_USER.toInfo());
                forwardToSearchPage(pwmRequest);
                return;
            }

            AuthenticationUtility.checkIfUserEligibleToAuthentication(pwmApplication, userIdentity);

            final ForgottenPasswordBean forgottenPasswordBean = forgottenPasswordBean(pwmRequest);
            initForgottenPasswordBean(pwmRequest, userIdentity, forgottenPasswordBean);

            // clear intruder search values
            pwmApplication.getIntruderManager().convenience().clearAttributes(formValues);
        } catch (PwmOperationalException e) {
            final ErrorInformation errorInfo = new ErrorInformation(PwmError.ERROR_RESPONSES_NORESPONSES,e.getErrorInformation().getDetailedErrorMsg(),e.getErrorInformation().getFieldValues());
            pwmApplication.getIntruderManager().mark(RecordType.ADDRESS, pwmSession.getSessionStateBean().getSrcAddress(), pwmRequest.getSessionLabel());
            pwmApplication.getIntruderManager().convenience().markAttributes(formValues, pwmSession);

            LOGGER.debug(pwmSession,errorInfo.toDebugStr());
            pwmRequest.setResponseError(errorInfo);
            forwardToSearchPage(pwmRequest);
        }
    }

    private void processEnterToken(final PwmRequest pwmRequest)
            throws ChaiUnavailableException, PwmUnrecoverableException, IOException, ServletException
    {
        final ForgottenPasswordBean forgottenPasswordBean = forgottenPasswordBean(pwmRequest);
        final String userEnteredCode = pwmRequest.readParameterAsString(PwmConstants.PARAM_TOKEN);

        ErrorInformation errorInformation = null;
        try {
            final TokenPayload tokenPayload = pwmRequest.getPwmApplication().getTokenService().processUserEnteredCode(
                    pwmRequest.getPwmSession(),
                    forgottenPasswordBean.getUserIdentity() == null ? null : forgottenPasswordBean.getUserIdentity(),
                    TokenType.FORGOTTEN_PW,
                    userEnteredCode
            );
            if (tokenPayload != null) {
                // token correct
                if (forgottenPasswordBean.getUserIdentity() == null) {
                    // clean session, user supplied token (clicked email, etc) and this is first request
                    initForgottenPasswordBean(
                            pwmRequest,
                            tokenPayload.getUserIdentity(),
                            forgottenPasswordBean
                    );
                }
                forgottenPasswordBean.getProgress().getSatisfiedMethods().add(IdentityVerificationMethod.TOKEN);
                StatisticsManager.incrementStat(pwmRequest.getPwmApplication(), Statistic.RECOVERY_TOKENS_PASSED);
            }
        } catch (PwmOperationalException e) {
            final String errorMsg = "token incorrect: " + e.getMessage();
            errorInformation = new ErrorInformation(PwmError.ERROR_TOKEN_INCORRECT,errorMsg);
        }

        if (!forgottenPasswordBean.getProgress().getSatisfiedMethods().contains(IdentityVerificationMethod.TOKEN)) {
            if (errorInformation == null) {
                errorInformation = new ErrorInformation(PwmError.ERROR_TOKEN_INCORRECT);
            }
            handleUserVerificationBadAttempt(pwmRequest, forgottenPasswordBean, errorInformation);
        }
    }

    private void processEnterNaaf(final PwmRequest pwmRequest)
            throws PwmUnrecoverableException, IOException, ServletException
    {
        final String PREFIX = "naaf-";
        final ForgottenPasswordBean forgottenPasswordBean = forgottenPasswordBean(pwmRequest);
        final VerificationMethodSystem naafMethod = forgottenPasswordBean.getProgress().getNaafRecoveryMethod();

        final Map<String,String> naafResponses = new LinkedHashMap<>();
        {
            final Map<String,String> inputMap = pwmRequest.readParametersAsMap();
            for (final String name : inputMap.keySet()) {
                if (name != null && name.startsWith(PREFIX)) {
                    final String strippedName = name.substring(PREFIX.length(), name.length());
                    final String value = inputMap.get(name);
                    naafResponses.put(strippedName,value);
                }
            }
        }

        final ErrorInformation errorInformation = naafMethod.respondToPrompts(naafResponses);

        if (naafMethod.getVerificationState() == VerificationMethodSystem.VerificationState.COMPLETE) {
            forgottenPasswordBean.getProgress().getSatisfiedMethods().add(IdentityVerificationMethod.NAAF);
        }

        if (naafMethod.getVerificationState() == VerificationMethodSystem.VerificationState.FAILED) {
            forgottenPasswordBean.getProgress().setNaafRecoveryMethod(null);
            pwmRequest.respondWithError(errorInformation,true);
            handleUserVerificationBadAttempt(pwmRequest, forgottenPasswordBean, errorInformation);
            LOGGER.debug(pwmRequest, "unsuccessful NAAF verification input: " + errorInformation.toDebugStr());
            return;
        }

        if (errorInformation != null) {
            pwmRequest.setResponseError(errorInformation);
            handleUserVerificationBadAttempt(pwmRequest, forgottenPasswordBean, errorInformation);
        }
    }

    private void processEnterRemote(final PwmRequest pwmRequest)
            throws PwmUnrecoverableException, IOException, ServletException
    {
        final String PREFIX = "remote-";
        final ForgottenPasswordBean forgottenPasswordBean = forgottenPasswordBean(pwmRequest);
        final VerificationMethodSystem remoteRecoveryMethod = forgottenPasswordBean.getProgress().getRemoteRecoveryMethod();

        final Map<String,String> remoteResponses = new LinkedHashMap<>();
        {
            final Map<String,String> inputMap = pwmRequest.readParametersAsMap();
            for (final String name : inputMap.keySet()) {
                if (name != null && name.startsWith(PREFIX)) {
                    final String strippedName = name.substring(PREFIX.length(), name.length());
                    final String value = inputMap.get(name);
                    remoteResponses.put(strippedName, value);
                }
            }
        }

        final ErrorInformation errorInformation = remoteRecoveryMethod.respondToPrompts(remoteResponses);

        if (remoteRecoveryMethod.getVerificationState() == VerificationMethodSystem.VerificationState.COMPLETE) {
            forgottenPasswordBean.getProgress().getSatisfiedMethods().add(IdentityVerificationMethod.REMOTE_RESPONSES);
        }

        if (remoteRecoveryMethod.getVerificationState() == VerificationMethodSystem.VerificationState.FAILED) {
            forgottenPasswordBean.getProgress().setNaafRecoveryMethod(null);
            pwmRequest.respondWithError(errorInformation,true);
            handleUserVerificationBadAttempt(pwmRequest, forgottenPasswordBean, errorInformation);
            LOGGER.debug(pwmRequest, "unsuccessful remote response verification input: " + errorInformation.toDebugStr());
            return;
        }

        if (errorInformation != null) {
            pwmRequest.setResponseError(errorInformation);
            handleUserVerificationBadAttempt(pwmRequest, forgottenPasswordBean, errorInformation);
        }
    }

    private void processEnterOtpToken(final PwmRequest pwmRequest)
            throws IOException, ServletException, PwmUnrecoverableException, ChaiUnavailableException
    {
        final ForgottenPasswordBean forgottenPasswordBean = forgottenPasswordBean(pwmRequest);
        final String userEnteredCode = pwmRequest.readParameterAsString(PwmConstants.PARAM_TOKEN);
        LOGGER.debug(pwmRequest, String.format("entered OTP: %s", userEnteredCode));

        final UserInfoBean userInfoBean = readUserInfoBean(pwmRequest, forgottenPasswordBean);
        final OTPUserRecord otpUserRecord = userInfoBean.getOtpUserRecord();

        final boolean otpPassed;
        if (otpUserRecord != null) {
            LOGGER.info(pwmRequest, "checking entered OTP");
            try {
                // forces service to use proxy account to update (write) updated otp record if necessary.
                otpPassed = pwmRequest.getPwmApplication().getOtpService().validateToken(
                        null,
                        forgottenPasswordBean.getUserIdentity(),
                        otpUserRecord,
                        userEnteredCode,
                        true
                );

                if (otpPassed) {
                    StatisticsManager.incrementStat(pwmRequest, Statistic.RECOVERY_OTP_PASSED);
                    LOGGER.debug(pwmRequest, "one time password validation has been passed");
                    forgottenPasswordBean.getProgress().getSatisfiedMethods().add(IdentityVerificationMethod.OTP);
                } else {
                    StatisticsManager.incrementStat(pwmRequest, Statistic.RECOVERY_OTP_FAILED);
                    handleUserVerificationBadAttempt(pwmRequest, forgottenPasswordBean, new ErrorInformation(PwmError.ERROR_INCORRECT_OTP_TOKEN));
                }
            } catch (PwmOperationalException e) {
                handleUserVerificationBadAttempt(pwmRequest, forgottenPasswordBean, new ErrorInformation(PwmError.ERROR_INCORRECT_OTP_TOKEN,e.getErrorInformation().toDebugStr()));
            }
        }
    }

    private void processOAuthReturn(final PwmRequest pwmRequest)
            throws IOException, ServletException, PwmUnrecoverableException, ChaiUnavailableException
    {
        final ForgottenPasswordBean forgottenPasswordBean = forgottenPasswordBean(pwmRequest);
        if (forgottenPasswordBean.getProgress().getInProgressVerificationMethod() != IdentityVerificationMethod.OAUTH) {
            LOGGER.debug(pwmRequest, "oauth return detected, however current session did not issue an oauth request; will restart forgotten password sequence");
            pwmRequest.getPwmApplication().getSessionStateService().clearBean(pwmRequest, ForgottenPasswordBean.class);
            pwmRequest.sendRedirect(PwmServletDefinition.ForgottenPassword);
            return;
        }

        if (forgottenPasswordBean.getUserIdentity() == null) {
            LOGGER.debug(pwmRequest, "oauth return detected, however current session does not have a user identity stored; will restart forgotten password sequence");
            pwmRequest.getPwmApplication().getSessionStateService().clearBean(pwmRequest, ForgottenPasswordBean.class);
            pwmRequest.sendRedirect(PwmServletDefinition.ForgottenPassword);
            return;
        }

        final String encryptedResult = pwmRequest.readParameterAsString(PwmConstants.PARAM_RECOVERY_OAUTH_RESULT, PwmHttpRequestWrapper.Flag.BypassValidation);
        final OAuthForgottenPasswordResults results = pwmRequest.getPwmApplication().getSecureService().decryptObject(encryptedResult, OAuthForgottenPasswordResults.class);
        LOGGER.trace(pwmRequest, "received ");

        final String userDNfromOAuth = results.getUsername();
        if (userDNfromOAuth == null || userDNfromOAuth.isEmpty()) {
            final String errorMsg = "oauth server coderesolver endpoint did not return a username value";
            final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_OAUTH_ERROR, errorMsg);
            throw new PwmUnrecoverableException(errorInformation);
        }

        final UserIdentity oauthUserIdentity;
        {
            final UserSearchEngine userSearchEngine = new UserSearchEngine(pwmRequest);
            try {
                oauthUserIdentity = userSearchEngine.resolveUsername(userDNfromOAuth, null, null);
            } catch (PwmOperationalException e) {
                final String errorMsg = "unexpected error searching for oauth supplied username in ldap; error: " + e.getMessage() ;
                final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_OAUTH_ERROR, errorMsg);
                throw new PwmUnrecoverableException(errorInformation);
            }
        }

        final boolean userMatch;
        {
            final UserIdentity userIdentityInBean = forgottenPasswordBean.getUserIdentity();
            userMatch = userIdentityInBean != null && userIdentityInBean.equals(oauthUserIdentity);
        }

        if (userMatch) {
            forgottenPasswordBean.getProgress().getSatisfiedMethods().add(IdentityVerificationMethod.OAUTH);
        } else {
            final String errorMsg = "oauth server username does not match previously identified user";
            final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_OAUTH_ERROR, errorMsg);
            throw new PwmUnrecoverableException(errorInformation);
        }
    }

    private void processCheckResponses(final PwmRequest pwmRequest)
            throws ChaiUnavailableException, IOException, ServletException, PwmUnrecoverableException
    {
        //final SessionStateBean ssBean = pwmRequest.getPwmSession().getSessionStateBean();
        final ForgottenPasswordBean forgottenPasswordBean = forgottenPasswordBean(pwmRequest);

        if (forgottenPasswordBean.getUserIdentity() == null) {
            return;
        }
        final UserIdentity userIdentity = forgottenPasswordBean.getUserIdentity();

        final ResponseSet responseSet = readResponseSet(pwmRequest, forgottenPasswordBean);
        if (responseSet == null) {
            final String errorMsg = "attempt to check responses, but responses are not loaded into session bean";
            final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_UNKNOWN, errorMsg);
            throw new PwmUnrecoverableException(errorInformation);
        }

        try {
            // read the supplied responses from the user
            final Map<Challenge, String> crMap = readResponsesFromHttpRequest(pwmRequest, forgottenPasswordBean.getPresentableChallengeSet());

            final boolean responsesPassed;
            try {
                responsesPassed = responseSet.test(crMap);
            } catch (ChaiUnavailableException e) {
                if (e.getCause() instanceof PwmUnrecoverableException) {
                    throw (PwmUnrecoverableException)e.getCause();
                }
                throw e;
            }

            // special case for nmas, clear out existing challenges and input fields.
            if (!responsesPassed && responseSet instanceof NMASCrOperator.NMASCRResponseSet) {
                forgottenPasswordBean.setPresentableChallengeSet(responseSet.getPresentableChallengeSet());
            }

            if (responsesPassed) {
                LOGGER.debug(pwmRequest, "user '" + userIdentity + "' has supplied correct responses");
            } else {
                final String errorMsg = "incorrect response to one or more challenges";
                final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_INCORRECT_RESPONSE, errorMsg);
                handleUserVerificationBadAttempt(pwmRequest, forgottenPasswordBean, errorInformation);
                return;
            }
        } catch (ChaiValidationException e) {
            LOGGER.debug(pwmRequest, "chai validation error checking user responses: " + e.getMessage());
            final ErrorInformation errorInformation = new ErrorInformation(PwmError.forChaiError(e.getErrorCode()));
            handleUserVerificationBadAttempt(pwmRequest, forgottenPasswordBean, errorInformation);
            return;
        }

        forgottenPasswordBean.getProgress().getSatisfiedMethods().add(IdentityVerificationMethod.CHALLENGE_RESPONSES);
    }

    private void processCheckAttributes(final PwmRequest pwmRequest)
            throws ChaiUnavailableException, IOException, ServletException, PwmUnrecoverableException
    {
        //final SessionStateBean ssBean = pwmRequest.getPwmSession().getSessionStateBean();
        final ForgottenPasswordBean forgottenPasswordBean = forgottenPasswordBean(pwmRequest);

        if (forgottenPasswordBean.getUserIdentity() == null) {
            return;
        }
        final UserIdentity userIdentity =forgottenPasswordBean.getUserIdentity();

        try { // check attributes
            final ChaiUser theUser = pwmRequest.getPwmApplication().getProxiedChaiUser(userIdentity);
            final Locale userLocale = pwmRequest.getLocale();

            final List<FormConfiguration> requiredAttributesForm = forgottenPasswordBean.getAttributeForm();

            if (requiredAttributesForm.isEmpty()) {
                return;
            }

            final Map<FormConfiguration,String> formValues = FormUtility.readFormValuesFromRequest(
                    pwmRequest, requiredAttributesForm, userLocale);
            for (final FormConfiguration paramConfig : formValues.keySet()) {
                final String attrName = paramConfig.getName();

                try {
                    if (theUser.compareStringAttribute(attrName, formValues.get(paramConfig))) {
                        LOGGER.trace(pwmRequest, "successful validation of ldap attribute value for '" + attrName + "'");
                    } else {
                        throw new PwmDataValidationException(new ErrorInformation(PwmError.ERROR_INCORRECT_RESPONSE, "incorrect value for '" + attrName + "'", new String[]{attrName}));
                    }
                } catch (ChaiOperationException e) {
                    LOGGER.error(pwmRequest, "error during param validation of '" + attrName + "', error: " + e.getMessage());
                    throw new PwmDataValidationException(new ErrorInformation(PwmError.ERROR_INCORRECT_RESPONSE, "ldap error testing value for '" + attrName + "'", new String[]{attrName}));
                }
            }

            forgottenPasswordBean.getProgress().getSatisfiedMethods().add(IdentityVerificationMethod.ATTRIBUTES);
        } catch (PwmDataValidationException e) {
            handleUserVerificationBadAttempt(pwmRequest, forgottenPasswordBean, new ErrorInformation(PwmError.ERROR_INCORRECT_RESPONSE,e.getErrorInformation().toDebugStr()));
        }
    }

    private void advancedToNextStage(final PwmRequest pwmRequest)
            throws IOException, ServletException, PwmUnrecoverableException, ChaiUnavailableException
    {
        final PwmApplication pwmApplication = pwmRequest.getPwmApplication();
        final Configuration config = pwmRequest.getConfig();
        final ForgottenPasswordBean forgottenPasswordBean = forgottenPasswordBean(pwmRequest);

        final ForgottenPasswordBean.RecoveryFlags recoveryFlags = forgottenPasswordBean.getRecoveryFlags();
        final ForgottenPasswordBean.Progress progress = forgottenPasswordBean.getProgress();

        // check for identified user;
        if (forgottenPasswordBean.getUserIdentity() == null) {
            forwardToSearchPage(pwmRequest);
            return;
        }

        final ForgottenPasswordProfile forgottenPasswordProfile = pwmRequest.getConfig().getForgottenPasswordProfiles().get(forgottenPasswordBean.getForgottenPasswordProfileID());
        {
            final Map<String, ForgottenPasswordProfile> profileIDList = pwmRequest.getConfig().getForgottenPasswordProfiles();
            final String profileDebugMsg = forgottenPasswordProfile != null && profileIDList != null && profileIDList.size() > 1
                    ? " profile=" + forgottenPasswordProfile.getIdentifier() + ", "
                    : "";
            LOGGER.trace(pwmRequest, "entering forgotten password progress engine: "
                    + profileDebugMsg
                    + "flags=" + JsonUtil.serialize(recoveryFlags) + ", "
                    + "progress=" + JsonUtil.serialize(progress));
        }

        if (forgottenPasswordProfile == null) {
            throw new PwmUnrecoverableException(new ErrorInformation(PwmError.ERROR_NO_PROFILE_ASSIGNED));
        }


        // check for previous authentication
        if (recoveryFlags.getRequiredAuthMethods().contains(IdentityVerificationMethod.PREVIOUS_AUTH) || recoveryFlags.getOptionalAuthMethods().contains(IdentityVerificationMethod.PREVIOUS_AUTH)) {
            if (!progress.getSatisfiedMethods().contains(IdentityVerificationMethod.PREVIOUS_AUTH)) {
                final UserIdentity userIdentity = forgottenPasswordBean.getUserIdentity();
                final String userGuid = LdapOperationsHelper.readLdapGuidValue(pwmApplication, pwmRequest.getSessionLabel(), userIdentity, true);
                if (checkAuthRecord(pwmRequest, userGuid)) {
                    LOGGER.debug(pwmRequest, "marking " + IdentityVerificationMethod.PREVIOUS_AUTH + " method as satisfied");
                    progress.getSatisfiedMethods().add(IdentityVerificationMethod.PREVIOUS_AUTH);
                }
            }
        }

        // dispatch required auth methods.
        for (final IdentityVerificationMethod method : recoveryFlags.getRequiredAuthMethods()) {
            if (!progress.getSatisfiedMethods().contains(method)) {
                forwardUserBasedOnRecoveryMethod(pwmRequest, method);
                return;
            }
        }

        // redirect if an verification method is in progress
        if (progress.getInProgressVerificationMethod() != null) {
            if (progress.getSatisfiedMethods().contains(progress.getInProgressVerificationMethod())) {
                progress.setInProgressVerificationMethod(null);
            } else {
                pwmRequest.setAttribute(PwmRequest.Attribute.ForgottenPasswordOptionalPageView,"true");
                forwardUserBasedOnRecoveryMethod(pwmRequest, progress.getInProgressVerificationMethod());
                return;
            }
        }

        // check if more optional methods required
        if (recoveryFlags.getMinimumOptionalAuthMethods() > 0) {
            final Set<IdentityVerificationMethod> satisfiedOptionalMethods = figureSatisfiedOptionalAuthMethods(recoveryFlags,progress);
            if (satisfiedOptionalMethods.size() < recoveryFlags.getMinimumOptionalAuthMethods()) {
                final Set<IdentityVerificationMethod> remainingAvailableOptionalMethods = figureRemainingAvailableOptionalAuthMethods(pwmRequest, forgottenPasswordBean);
                if (remainingAvailableOptionalMethods.isEmpty()) {
                    final String errorMsg = "additional optional verification methods are needed, however all available optional verification methods have been satisified by user";
                    final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_INVALID_CONFIG,errorMsg);
                    LOGGER.error(pwmRequest, errorInformation);
                    throw new PwmUnrecoverableException(errorInformation);
                } else {
                    if (remainingAvailableOptionalMethods.size() == 1) {
                        final IdentityVerificationMethod remainingMethod = remainingAvailableOptionalMethods.iterator().next();
                        LOGGER.debug(pwmRequest, "only 1 remaining available optional verification method, will redirect to " + remainingMethod.toString());
                        forwardUserBasedOnRecoveryMethod(pwmRequest, remainingMethod);
                        progress.setInProgressVerificationMethod(remainingMethod);
                        return;
                    }
                }
                processVerificationChoice(pwmRequest);
                return;
            }
        }

        if (progress.getSatisfiedMethods().isEmpty()) {
            final String errorMsg = "forgotten password recovery sequence completed, but user has not actually satisfied any verification methods";
            final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_INVALID_CONFIG,errorMsg);
            LOGGER.error(pwmRequest, errorInformation);
            throw new PwmUnrecoverableException(errorInformation);
        }

        if (!forgottenPasswordBean.getProgress().isAllPassed()) {
            forgottenPasswordBean.getProgress().setAllPassed(true);
            StatisticsManager.incrementStat(pwmRequest, Statistic.RECOVERY_SUCCESSES);
        }

        final UserInfoBean userInfoBean = readUserInfoBean(pwmRequest, forgottenPasswordBean);
        try {
            final boolean enforceFromForgotten = pwmApplication.getConfig().readSettingAsBoolean(PwmSetting.CHALLENGE_ENFORCE_MINIMUM_PASSWORD_LIFETIME);
            if (enforceFromForgotten) {
                final ChaiUser theUser = pwmApplication.getProxiedChaiUser(forgottenPasswordBean.getUserIdentity());
                PasswordUtility.checkIfPasswordWithinMinimumLifetime(
                        theUser,
                        pwmRequest.getSessionLabel(),
                        userInfoBean.getPasswordPolicy(),
                        userInfoBean.getLastLdapLoginTime(),
                        userInfoBean.getPasswordState()
                );
            }
        } catch (PwmOperationalException e) {
            throw new PwmUnrecoverableException(e.getErrorInformation());
        }

        LOGGER.trace(pwmRequest, "all recovery checks passed, proceeding to configured recovery action");

        final RecoveryAction recoveryAction = getRecoveryAction(config, forgottenPasswordBean);
        if (recoveryAction == RecoveryAction.SENDNEWPW || recoveryAction == RecoveryAction.SENDNEWPW_AND_EXPIRE) {
            processSendNewPassword(pwmRequest);
            return;
        }

        if (forgottenPasswordProfile.readSettingAsBoolean(PwmSetting.RECOVERY_ALLOW_UNLOCK)) {
            final PasswordStatus passwordStatus = userInfoBean.getPasswordState();

            if (!passwordStatus.isExpired() && !passwordStatus.isPreExpired()) {
                try {
                    final ChaiUser theUser = pwmApplication.getProxiedChaiUser(forgottenPasswordBean.getUserIdentity());
                    if (theUser.isPasswordLocked()) {
                        pwmRequest.forwardToJsp(PwmConstants.JspUrl.RECOVER_PASSWORD_ACTION_CHOICE);
                        return;
                    }
                } catch (ChaiOperationException e) {
                    LOGGER.error(pwmRequest, "chai operation error checking user lock status: " + e.getMessage());
                }
            }
        }

        this.executeResetPassword(pwmRequest);
    }


    private void executeUnlock(final PwmRequest pwmRequest)
            throws IOException, ServletException, ChaiUnavailableException, PwmUnrecoverableException
    {
        final PwmApplication pwmApplication = pwmRequest.getPwmApplication();
        final PwmSession pwmSession = pwmRequest.getPwmSession();
        final ForgottenPasswordBean forgottenPasswordBean = forgottenPasswordBean(pwmRequest);
        final UserIdentity userIdentity = forgottenPasswordBean.getUserIdentity();

        try {
            final ChaiUser theUser = pwmApplication.getProxiedChaiUser(userIdentity);
            theUser.unlockPassword();

            // mark the event log
            final UserInfoBean userInfoBean = readUserInfoBean(pwmRequest, forgottenPasswordBean);
            pwmApplication.getAuditManager().submit(AuditEvent.UNLOCK_PASSWORD, userInfoBean, pwmSession);

            pwmRequest.getPwmResponse().forwardToSuccessPage(Message.Success_UnlockAccount);
        } catch (ChaiOperationException e) {
            final String errorMsg = "unable to unlock user " + userIdentity + " error: " + e.getMessage();
            final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_UNLOCK_FAILURE,errorMsg);
            LOGGER.error(pwmSession, errorInformation.toDebugStr());
            pwmRequest.respondWithError(errorInformation, true);
        } finally {
            clearForgottenPasswordBean(pwmRequest);
        }
    }


    private void executeResetPassword(final PwmRequest pwmRequest)
            throws ChaiUnavailableException, IOException, ServletException, PwmUnrecoverableException
    {
        final PwmApplication pwmApplication = pwmRequest.getPwmApplication();
        final PwmSession pwmSession = pwmRequest.getPwmSession();
        final ForgottenPasswordBean forgottenPasswordBean = forgottenPasswordBean(pwmRequest);

        if (!forgottenPasswordBean.getProgress().isAllPassed()) {
            return;
        }

        final UserIdentity userIdentity = forgottenPasswordBean.getUserIdentity();
        final ChaiUser theUser = pwmApplication.getProxiedChaiUser(userIdentity);

        try { // try unlocking user
            theUser.unlockPassword();
            LOGGER.trace(pwmSession, "unlock account succeeded");
        } catch (ChaiOperationException e) {
            final String errorMsg = "unable to unlock user " + theUser.getEntryDN() + " error: " + e.getMessage();
            final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_UNLOCK_FAILURE,errorMsg);
            LOGGER.error(pwmSession, errorInformation.toDebugStr());
        }

        try {
            final SessionAuthenticator sessionAuthenticator = new SessionAuthenticator(
                    pwmApplication,
                    pwmSession,
                    PwmAuthenticationSource.FORGOTTEN_PASSWORD
            );
            sessionAuthenticator.authUserWithUnknownPassword(userIdentity,AuthenticationType.AUTH_FROM_PUBLIC_MODULE);
            pwmSession.getLoginInfoBean().getAuthFlags().add(AuthenticationType.AUTH_FROM_PUBLIC_MODULE);

            LOGGER.info(pwmSession, "user successfully supplied password recovery responses, forward to change password page: " + theUser.getEntryDN());

            // mark the event log
            pwmApplication.getAuditManager().submit(AuditEvent.RECOVER_PASSWORD, pwmSession.getUserInfoBean(),
                    pwmSession);

            // add the post-forgotten password actions
            addPostChangeAction(pwmRequest, userIdentity);

            // mark user as requiring a new password.
            pwmSession.getUserInfoBean().setRequiresNewPassword(true);

            // redirect user to change password screen.
            pwmRequest.sendRedirect(PwmServletDefinition.ChangePassword.servletUrlName());
        } catch (PwmUnrecoverableException e) {
            LOGGER.warn(pwmSession,
                    "unexpected error authenticating during forgotten password recovery process user: " + e.getMessage());
            pwmRequest.respondWithError(e.getErrorInformation());
        } finally {
            clearForgottenPasswordBean(pwmRequest);
        }
    }

    private void processSendNewPassword(final PwmRequest pwmRequest)
            throws ChaiUnavailableException, IOException, ServletException, PwmUnrecoverableException
    {
        final PwmApplication pwmApplication = pwmRequest.getPwmApplication();
        final PwmSession pwmSession = pwmRequest.getPwmSession();
        final ForgottenPasswordBean forgottenPasswordBean = forgottenPasswordBean(pwmRequest);
        final ForgottenPasswordProfile forgottenPasswordProfile = pwmRequest.getConfig().getForgottenPasswordProfiles().get(forgottenPasswordBean.getForgottenPasswordProfileID());
        final RecoveryAction recoveryAction = getRecoveryAction(pwmApplication.getConfig(), forgottenPasswordBean);

        LOGGER.trace(pwmRequest,"beginning process to send new password to user");

        if (!forgottenPasswordBean.getProgress().isAllPassed()) {
            return;
        }

        final UserIdentity userIdentity = forgottenPasswordBean.getUserIdentity();
        final ChaiUser theUser = pwmRequest.getPwmApplication().getProxiedChaiUser(userIdentity);

        try { // try unlocking user
            theUser.unlockPassword();
            LOGGER.trace(pwmRequest, "unlock account succeeded");
        } catch (ChaiOperationException e) {
            final String errorMsg = "unable to unlock user " + theUser.getEntryDN() + " error: " + e.getMessage();
            final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_UNLOCK_FAILURE,errorMsg);
            LOGGER.error(pwmRequest.getPwmSession(), errorInformation.toDebugStr());
            pwmRequest.respondWithError(errorInformation);
            return;
        }

        try {
            final SessionAuthenticator sessionAuthenticator = new SessionAuthenticator(
                    pwmApplication,
                    pwmSession,
                    PwmAuthenticationSource.FORGOTTEN_PASSWORD
            );
            sessionAuthenticator.authUserWithUnknownPassword(userIdentity,AuthenticationType.AUTH_FROM_PUBLIC_MODULE);
            pwmSession.getLoginInfoBean().getAuthFlags().add(AuthenticationType.AUTH_FROM_PUBLIC_MODULE);


            LOGGER.info(pwmRequest, "user successfully supplied password recovery responses, emailing new password to: " + theUser.getEntryDN());

            // add post change actions
            addPostChangeAction(pwmRequest, userIdentity);

            // create newpassword
            final PasswordData newPassword = RandomPasswordGenerator.createRandomPassword(pwmSession, pwmApplication);

            // set the password
            LOGGER.trace(pwmRequest.getPwmSession(), "setting user password to system generated random value");
            PasswordUtility.setActorPassword(pwmSession, pwmApplication, newPassword);

            if (recoveryAction == RecoveryAction.SENDNEWPW_AND_EXPIRE) {
                LOGGER.debug(pwmSession, "marking user password as expired");
                theUser.expirePassword();
            }

            // mark the event log
            pwmApplication.getAuditManager().submit(AuditEvent.RECOVER_PASSWORD, pwmSession.getUserInfoBean(), pwmSession);

            final MessageSendMethod messageSendMethod = forgottenPasswordProfile.readSettingAsEnum(PwmSetting.RECOVERY_SENDNEWPW_METHOD,MessageSendMethod.class);

            // send email or SMS
            final String toAddress = PasswordUtility.sendNewPassword(
                    pwmSession.getUserInfoBean(),
                    pwmApplication,
                    pwmSession.getSessionManager().getMacroMachine(pwmApplication),
                    newPassword,
                    pwmSession.getSessionStateBean().getLocale(),
                    messageSendMethod
            );

            pwmRequest.getPwmResponse().forwardToSuccessPage(Message.Success_PasswordSend, toAddress);
        } catch (PwmException e) {
            LOGGER.warn(pwmSession,"unexpected error setting new password during recovery process for user: " + e.getMessage());
            pwmRequest.respondWithError(e.getErrorInformation());
        } catch (ChaiOperationException e) {
            final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_UNKNOWN,"unexpected ldap error while processing recovery action " + recoveryAction + ", error: " + e.getMessage());
            LOGGER.warn(pwmSession,errorInformation.toDebugStr());
            pwmRequest.respondWithError(errorInformation);
        } finally {
            clearForgottenPasswordBean(pwmRequest);
            pwmSession.unauthenticateUser(pwmRequest);
            pwmSession.getSessionStateBean().setPasswordModified(false);
        }
    }

    public static Map<Challenge, String> readResponsesFromHttpRequest(
            final PwmRequest req,
            final ChallengeSet challengeSet
    )
            throws ChaiValidationException, ChaiUnavailableException, PwmUnrecoverableException
    {
        final Map<Challenge, String> responses = new LinkedHashMap<>();

        int counter = 0;
        for (final Challenge loopChallenge : challengeSet.getChallenges()) {
            counter++;
            final String answer = req.readParameterAsString(PwmConstants.PARAM_RESPONSE_PREFIX + counter);

            responses.put(loopChallenge, answer.length() > 0 ? answer : "");
        }

        return responses;
    }

    private static String initializeAndSendToken(
            final PwmRequest pwmRequest,
            final UserInfoBean userInfoBean,
            final MessageSendMethod tokenSendMethod

    )
            throws PwmUnrecoverableException
    {
        final Configuration config = pwmRequest.getConfig();
        final UserIdentity userIdentity = userInfoBean.getUserIdentity();
        final Map<String,String> tokenMapData = new HashMap<>();


        try {
            final Date userLastPasswordChange = PasswordUtility.determinePwdLastModified(
                    pwmRequest.getPwmApplication(),
                    pwmRequest.getSessionLabel(),
                    userIdentity
            );
            if (userLastPasswordChange != null) {
                final String userChangeString = PwmConstants.DEFAULT_DATETIME_FORMAT.format(userLastPasswordChange);
                tokenMapData.put(PwmConstants.TOKEN_KEY_PWD_CHG_DATE, userChangeString);
            }
        } catch (ChaiUnavailableException e) {
            LOGGER.error(pwmRequest, "unexpected error reading user's last password change time");
        }

        final EmailItemBean emailItemBean = config.readSettingAsEmail(PwmSetting.EMAIL_CHALLENGE_TOKEN, pwmRequest.getLocale());
        final MacroMachine macroMachine = MacroMachine.forUser(pwmRequest, userIdentity);

        final RestTokenDataClient.TokenDestinationData inputDestinationData = new RestTokenDataClient.TokenDestinationData(
                macroMachine.expandMacros(emailItemBean.getTo()),
                userInfoBean.getUserSmsNumber(),
                null
        );

        final RestTokenDataClient restTokenDataClient = new RestTokenDataClient(pwmRequest.getPwmApplication());
        final RestTokenDataClient.TokenDestinationData outputDestrestTokenDataClient = restTokenDataClient.figureDestTokenDisplayString(
                pwmRequest.getSessionLabel(),
                inputDestinationData,
                userIdentity,
                pwmRequest.getLocale());

        final String tokenDestinationAddress = outputDestrestTokenDataClient.getDisplayValue();
        final Set<String> destinationValues = new HashSet<>();
        if (outputDestrestTokenDataClient.getEmail() != null) {
            destinationValues.add(outputDestrestTokenDataClient.getEmail());
        }
        if (outputDestrestTokenDataClient.getSms() != null) {
            destinationValues.add(outputDestrestTokenDataClient.getSms());
        }

        final String tokenKey;
        final TokenPayload tokenPayload;
        try {
            tokenPayload = pwmRequest.getPwmApplication().getTokenService().createTokenPayload(TokenType.FORGOTTEN_PW, tokenMapData, userIdentity, destinationValues);
            tokenKey = pwmRequest.getPwmApplication().getTokenService().generateNewToken(tokenPayload, pwmRequest.getSessionLabel());
        } catch (PwmOperationalException e) {
            throw new PwmUnrecoverableException(e.getErrorInformation());
        }

        final String smsMessage = config.readSettingAsLocalizedString(PwmSetting.SMS_CHALLENGE_TOKEN_TEXT, pwmRequest.getLocale());

        TokenService.TokenSender.sendToken(
                pwmRequest.getPwmApplication(),
                userInfoBean,
                macroMachine,
                emailItemBean,
                tokenSendMethod,
                outputDestrestTokenDataClient.getEmail(),
                outputDestrestTokenDataClient.getSms(),
                smsMessage,
                tokenKey
        );

        StatisticsManager.incrementStat(pwmRequest, Statistic.RECOVERY_TOKENS_SENT);
        return tokenDestinationAddress;
    }

    private static List<FormConfiguration> figureAttributeForm(
            final PwmApplication pwmApplication,
            final ForgottenPasswordProfile forgottenPasswordProfile,
            final SessionLabel sessionLabel,
            final UserIdentity userIdentity
    )
            throws ChaiUnavailableException, PwmOperationalException, PwmUnrecoverableException
    {
        final List<FormConfiguration> requiredAttributesForm = forgottenPasswordProfile.readSettingAsForm(PwmSetting.RECOVERY_ATTRIBUTE_FORM);
        if (requiredAttributesForm.isEmpty()) {
            return requiredAttributesForm;
        }

        final UserDataReader userDataReader = LdapUserDataReader.appProxiedReader(pwmApplication, userIdentity);
        final List<FormConfiguration> returnList = new ArrayList<>();
        for (final FormConfiguration formItem : requiredAttributesForm) {
            if (formItem.isRequired()) {
                returnList.add(formItem);
            } else {
                try {
                    final String currentValue = userDataReader.readStringAttribute(formItem.getName());
                    if (currentValue != null && currentValue.length() > 0) {
                        returnList.add(formItem);
                    } else {
                        LOGGER.trace(sessionLabel, "excluding optional required attribute(" + formItem.getName() + "), user has no value");
                    }
                } catch (ChaiOperationException e) {
                    throw new PwmOperationalException(new ErrorInformation(PwmError.ERROR_NO_CHALLENGES, "unexpected error reading value for attribute " + formItem.getName()));
                }
            }
        }

        if (returnList.isEmpty()) {
            throw new PwmOperationalException(new ErrorInformation(PwmError.ERROR_NO_CHALLENGES, "user has no values for any optional attribute"));
        }

        return returnList;
    }

    private void addPostChangeAction(
            final PwmRequest pwmRequest,
            final UserIdentity userIdentity
    )
    {
        final PostChangePasswordAction postAction = new PostChangePasswordAction() {
            @Override
            public String getLabel() {
                return "Forgotten Password Post Actions";
            }

            @Override
            public boolean doAction(final PwmSession pwmSession, final String newPassword)
                    throws PwmUnrecoverableException {
                try {
                    {  // execute configured actions
                        final ChaiUser proxiedUser = pwmRequest.getPwmApplication().getProxiedChaiUser(userIdentity);
                        LOGGER.debug(pwmSession, "executing post-forgotten password configured actions to user " + proxiedUser.getEntryDN());
                        final List<ActionConfiguration> configValues = pwmRequest.getConfig().readSettingAsAction(PwmSetting.FORGOTTEN_USER_POST_ACTIONS);
                        final ActionExecutor actionExecutor = new ActionExecutor.ActionExecutorSettings(pwmRequest.getPwmApplication(),userIdentity)
                                .setMacroMachine(pwmSession.getSessionManager().getMacroMachine(pwmRequest.getPwmApplication()))
                                .setExpandPwmMacros(true)
                                .createActionExecutor();

                        actionExecutor.executeActions(configValues, pwmSession);
                    }
                } catch (PwmOperationalException e) {
                    final ErrorInformation info = new ErrorInformation(PwmError.ERROR_UNKNOWN, e.getErrorInformation().getDetailedErrorMsg(), e.getErrorInformation().getFieldValues());
                    final PwmUnrecoverableException newException = new PwmUnrecoverableException(info);
                    newException.initCause(e);
                    throw newException;
                } catch (ChaiUnavailableException e) {
                    final String errorMsg = "unable to reach ldap server while writing post-forgotten password attributes: " + e.getMessage();
                    final ErrorInformation info = new ErrorInformation(PwmError.ERROR_ACTIVATION_FAILURE, errorMsg);
                    final PwmUnrecoverableException newException = new PwmUnrecoverableException(info);
                    newException.initCause(e);
                    throw newException;
                }
                return true;
            }
        };

        pwmRequest.getPwmSession().getUserSessionDataCacheBean().addPostChangePasswordActions("forgottenPasswordPostActions", postAction);
    }

    private static void verifyRequirementsForAuthMethod(
            final PwmRequest pwmRequest,
            final ForgottenPasswordBean forgottenPasswordBean,
            final IdentityVerificationMethod recoveryVerificationMethods
    )
            throws PwmUnrecoverableException
    {
        switch (recoveryVerificationMethods) {
            case TOKEN: {
                final MessageSendMethod tokenSendMethod = forgottenPasswordBean.getRecoveryFlags().getTokenSendMethod();
                if (tokenSendMethod == null || tokenSendMethod == MessageSendMethod.NONE) {
                    final String errorMsg = "user is required to complete token validation, yet there is not a token send method configured";
                    final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_INVALID_CONFIG, errorMsg);
                    throw new PwmUnrecoverableException(errorInformation);
                }
            }
            break;

            case ATTRIBUTES: {
                final List<FormConfiguration> formConfiguration = forgottenPasswordBean.getAttributeForm();
                if (formConfiguration == null || formConfiguration.isEmpty()) {
                    final String errorMsg = "user is required to complete LDAP attribute check, yet there are no LDAP attribute form items configured";
                    final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_INVALID_CONFIG, errorMsg);
                    throw new PwmUnrecoverableException(errorInformation);
                }
            }
            break;

            case OTP: {
                final UserInfoBean userInfoBean = readUserInfoBean(pwmRequest, forgottenPasswordBean);
                if (userInfoBean.getOtpUserRecord() == null) {
                    final String errorMsg = "could not find a one time password configuration for " + userInfoBean.getUserIdentity();
                    final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_NO_OTP_CONFIGURATION, errorMsg);
                    throw new PwmUnrecoverableException(errorInformation);
                }
            }
            break;

            case CHALLENGE_RESPONSES: {
                final UserInfoBean userInfoBean = readUserInfoBean(pwmRequest, forgottenPasswordBean);
                final ResponseSet responseSet = readResponseSet(pwmRequest, forgottenPasswordBean);
                if (responseSet == null) {
                    final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_RESPONSES_NORESPONSES);
                    throw new PwmUnrecoverableException(errorInformation);
                }

                final ChallengeSet challengeSet = userInfoBean.getChallengeProfile().getChallengeSet();

                try {
                    if (responseSet.meetsChallengeSetRequirements(challengeSet)) {
                        if (challengeSet.getRequiredChallenges().isEmpty() && (challengeSet.getMinRandomRequired() <= 0)) {
                            final String errorMsg = "configured challenge set policy for " + userInfoBean.getUserIdentity().toString() + " is empty, user not qualified to recover password";
                            final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_NO_CHALLENGES, errorMsg);
                            throw new PwmUnrecoverableException(errorInformation);
                        }
                    }
                } catch (ChaiValidationException e) {
                    final String errorMsg = "stored response set for user '" + userInfoBean.getUserIdentity() + "' do not meet current challenge set requirements: " + e.getLocalizedMessage();
                    final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_RESPONSES_NORESPONSES, errorMsg);
                    throw new PwmUnrecoverableException(errorInformation);
                }
            }
            break;

            default:
                // continue, assume no data requirements for method.
                break;
        }
    }

    private static void initForgottenPasswordBean(
            final PwmRequest pwmRequest,
            final UserIdentity userIdentity,
            final ForgottenPasswordBean forgottenPasswordBean
    )
            throws PwmUnrecoverableException, PwmOperationalException
    {

        final PwmApplication pwmApplication = pwmRequest.getPwmApplication();
        final Locale locale = pwmRequest.getLocale();
        final SessionLabel sessionLabel = pwmRequest.getSessionLabel();

        forgottenPasswordBean.setUserIdentity(userIdentity);

        final UserInfoBean userInfoBean = readUserInfoBean(pwmRequest, forgottenPasswordBean);

        final String forgottenProfileID = ProfileUtility.discoverProfileIDforUser(pwmApplication, sessionLabel, userIdentity, ProfileType.ForgottenPassword);
        if (forgottenProfileID == null || forgottenProfileID.isEmpty()) {
            throw new PwmUnrecoverableException(PwmError.ERROR_NO_PROFILE_ASSIGNED.toInfo());
        }
        forgottenPasswordBean.setForgottenPasswordProfileID(forgottenProfileID);
        final ForgottenPasswordProfile forgottenPasswordProfile = pwmApplication.getConfig().getForgottenPasswordProfiles().get(forgottenProfileID);

        final ForgottenPasswordBean.RecoveryFlags recoveryFlags = calculateRecoveryFlags(
                pwmApplication,
                forgottenProfileID
        );

        final ChallengeSet challengeSet;
        if (recoveryFlags.getRequiredAuthMethods().contains(IdentityVerificationMethod.CHALLENGE_RESPONSES)
                || recoveryFlags.getOptionalAuthMethods().contains(IdentityVerificationMethod.CHALLENGE_RESPONSES)) {
            final ResponseSet responseSet;
            try {
                final ChaiUser theUser = pwmApplication.getProxiedChaiUser(userInfoBean.getUserIdentity());
                responseSet = pwmApplication.getCrService().readUserResponseSet(
                        sessionLabel,
                        userInfoBean.getUserIdentity(),
                        theUser
                );
                challengeSet = responseSet == null ? null : responseSet.getPresentableChallengeSet();
            } catch (ChaiValidationException e) {
                final String errorMsg = "unable to determine presentable challengeSet for stored responses: " + e.getMessage();
                final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_NO_CHALLENGES, errorMsg);
                throw new PwmUnrecoverableException(errorInformation);
            } catch (ChaiUnavailableException e) {
                throw new PwmUnrecoverableException(PwmError.forChaiError(e.getErrorCode()));
            }
        } else {
            challengeSet = null;
        }


        if (!recoveryFlags.isAllowWhenLdapIntruderLocked()) {
            try {
                final ChaiUser chaiUser = pwmApplication.getProxiedChaiUser(userInfoBean.getUserIdentity());
                if (chaiUser.isPasswordLocked()) {
                    throw new PwmUnrecoverableException(new ErrorInformation(PwmError.ERROR_INTRUDER_LDAP));
                }
            } catch (ChaiOperationException e) {
                final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_UNKNOWN,
                        "error checking user '" + userInfoBean.getUserIdentity() + "' ldap intruder lock status: " + e.getMessage());
                LOGGER.error(sessionLabel, errorInformation);
                throw new PwmUnrecoverableException(errorInformation);
            } catch (ChaiUnavailableException e) {
                throw new PwmUnrecoverableException(PwmError.forChaiError(e.getErrorCode()));
            }
        }

        final List<FormConfiguration> attributeForm;
        try {
            attributeForm = figureAttributeForm(pwmApplication, forgottenPasswordProfile, sessionLabel, userIdentity);
        } catch (ChaiUnavailableException e) {
            throw new PwmUnrecoverableException(PwmError.forChaiError(e.getErrorCode()));
        }

        forgottenPasswordBean.setUserLocale(locale);
        forgottenPasswordBean.setPresentableChallengeSet(challengeSet);
        forgottenPasswordBean.setAttributeForm(attributeForm);

        forgottenPasswordBean.setRecoveryFlags(recoveryFlags);
        forgottenPasswordBean.setProgress(new ForgottenPasswordBean.Progress());

        for (final IdentityVerificationMethod recoveryVerificationMethods : recoveryFlags.getRequiredAuthMethods()) {
            verifyRequirementsForAuthMethod(pwmRequest, forgottenPasswordBean, recoveryVerificationMethods);
        }
    }

    private static ForgottenPasswordBean.RecoveryFlags calculateRecoveryFlags(
            final PwmApplication pwmApplication,
            final String forgottenPasswordProfileID
    ) {
        final Configuration config = pwmApplication.getConfig();
        final ForgottenPasswordProfile forgottenPasswordProfile = config.getForgottenPasswordProfiles().get(forgottenPasswordProfileID);

        final MessageSendMethod tokenSendMethod = config.getForgottenPasswordProfiles().get(forgottenPasswordProfileID).readSettingAsEnum(PwmSetting.RECOVERY_TOKEN_SEND_METHOD, MessageSendMethod.class);

        final Set<IdentityVerificationMethod> requiredRecoveryVerificationMethods = forgottenPasswordProfile.requiredRecoveryAuthenticationMethods();
        final Set<IdentityVerificationMethod> optionalRecoveryVerificationMethods = forgottenPasswordProfile.optionalRecoveryAuthenticationMethods();
        final int minimumOptionalRecoveryAuthMethods = forgottenPasswordProfile.getMinOptionalRequired();
        final boolean allowWhenLdapIntruderLocked = forgottenPasswordProfile.readSettingAsBoolean(PwmSetting.RECOVERY_ALLOW_WHEN_LOCKED);

        return new ForgottenPasswordBean.RecoveryFlags(
                requiredRecoveryVerificationMethods,
                optionalRecoveryVerificationMethods,
                minimumOptionalRecoveryAuthMethods,
                allowWhenLdapIntruderLocked,
                tokenSendMethod
        );
    }

    private void handleUserVerificationBadAttempt(
            final PwmRequest pwmRequest,
            final ForgottenPasswordBean forgottenPasswordBean,
            final ErrorInformation errorInformation
    )
            throws PwmUnrecoverableException
    {
        LOGGER.debug(pwmRequest, errorInformation);
        pwmRequest.setResponseError(errorInformation);

        final UserIdentity userIdentity = forgottenPasswordBean == null
                ? null
                : forgottenPasswordBean.getUserIdentity();
        if (userIdentity != null) {
            final SessionAuthenticator sessionAuthenticator = new SessionAuthenticator(
                    pwmRequest.getPwmApplication(),
                    pwmRequest.getPwmSession(),
                    PwmAuthenticationSource.FORGOTTEN_PASSWORD
            );
            sessionAuthenticator.simulateBadPassword(userIdentity);
            pwmRequest.getPwmApplication().getIntruderManager().convenience().markUserIdentity(userIdentity,
                    pwmRequest.getPwmSession());
            pwmRequest.getPwmApplication().getIntruderManager().convenience().markAddressAndSession(
                    pwmRequest.getPwmSession());
        }
        StatisticsManager.incrementStat(pwmRequest, Statistic.RECOVERY_FAILURES);
    }

    private void checkForLocaleSwitch(final PwmRequest pwmRequest, final ForgottenPasswordBean forgottenPasswordBean)
            throws PwmUnrecoverableException, IOException, ServletException
    {
        if (forgottenPasswordBean.getUserIdentity() == null || forgottenPasswordBean.getUserLocale() == null) {
            return;
        }

        if (forgottenPasswordBean.getUserLocale().equals(pwmRequest.getLocale())) {
            return;
        }

        LOGGER.debug(pwmRequest, "user initiated forgotten password recovery using '" + forgottenPasswordBean.getUserLocale() + "' locale, but current request locale is now '"
                + pwmRequest.getLocale() + "', thus, the user progress will be restart and user data will be re-read using current locale");

        try {
            initForgottenPasswordBean(
                    pwmRequest,
                    forgottenPasswordBean.getUserIdentity(),
                    forgottenPasswordBean
            );
        } catch (PwmOperationalException e) {
            clearForgottenPasswordBean(pwmRequest);
            final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_UNKNOWN, "unexpected error while re-loading user data due to locale change: " + e.getErrorInformation().toDebugStr());
            LOGGER.error(pwmRequest, errorInformation.toDebugStr());
            pwmRequest.setResponseError(errorInformation);
        }
    }

    private static MessageSendMethod figureTokenSendPreference(
            final PwmRequest pwmRequest,
            final ForgottenPasswordBean forgottenPasswordBean
    )
            throws PwmUnrecoverableException
    {
        final UserInfoBean userInfoBean = readUserInfoBean(pwmRequest, forgottenPasswordBean);
        final MessageSendMethod tokenSendMethod = forgottenPasswordBean.getRecoveryFlags().getTokenSendMethod();
        if (tokenSendMethod == null || tokenSendMethod.equals(MessageSendMethod.NONE)) {
            return MessageSendMethod.NONE;
        }

        if (!tokenSendMethod.equals(MessageSendMethod.CHOICE_SMS_EMAIL)) {
            return tokenSendMethod;
        }

        final String emailAddress = userInfoBean.getUserEmailAddress();
        final String smsAddress = userInfoBean.getUserSmsNumber();

        final boolean hasEmail = emailAddress != null && emailAddress.length() > 1;
        final boolean hasSms = smsAddress != null && smsAddress.length() > 1;

        if (hasEmail && hasSms) {
            return MessageSendMethod.CHOICE_SMS_EMAIL;
        } else if (hasEmail) {
            LOGGER.debug(pwmRequest, "though token send method is " + MessageSendMethod.CHOICE_SMS_EMAIL + ", no sms address is available for user so defaulting to email method");
            return MessageSendMethod.EMAILONLY;
        } else if (hasSms) {
            LOGGER.debug(pwmRequest, "though token send method is " + MessageSendMethod.CHOICE_SMS_EMAIL + ", no email address is available for user so defaulting to sms method");
            return MessageSendMethod.SMSONLY;
        }

        throw new PwmUnrecoverableException(new ErrorInformation(PwmError.ERROR_TOKEN_MISSING_CONTACT));
    }

    private static Set<IdentityVerificationMethod> figureSatisfiedOptionalAuthMethods(
            final ForgottenPasswordBean.RecoveryFlags recoveryFlags,
            final ForgottenPasswordBean.Progress progress)
    {
        final Set<IdentityVerificationMethod> result = new HashSet<>();
        result.addAll(recoveryFlags.getOptionalAuthMethods());
        result.retainAll(progress.getSatisfiedMethods());
        return Collections.unmodifiableSet(result);
    }

    private static Set<IdentityVerificationMethod> figureRemainingAvailableOptionalAuthMethods(
            final PwmRequest pwmRequest,
            final ForgottenPasswordBean forgottenPasswordBean
    )
    {
        final ForgottenPasswordBean.RecoveryFlags recoveryFlags = forgottenPasswordBean.getRecoveryFlags();
        final ForgottenPasswordBean.Progress progress = forgottenPasswordBean.getProgress();
        final Set<IdentityVerificationMethod> result = new HashSet<>();
        result.addAll(recoveryFlags.getOptionalAuthMethods());
        result.removeAll(progress.getSatisfiedMethods());

        for (final IdentityVerificationMethod recoveryVerificationMethods : new HashSet<>(result)) {
            try {
                verifyRequirementsForAuthMethod(pwmRequest, forgottenPasswordBean, recoveryVerificationMethods);
            } catch (PwmUnrecoverableException e) {
                result.remove(recoveryVerificationMethods);
            }
        }

        return Collections.unmodifiableSet(result);
    }

    public RecoveryAction getRecoveryAction(final Configuration configuration, final ForgottenPasswordBean forgottenPasswordBean) {
        final ForgottenPasswordProfile forgottenPasswordProfile = configuration.getForgottenPasswordProfiles().get(forgottenPasswordBean.getForgottenPasswordProfileID());
        return forgottenPasswordProfile.readSettingAsEnum(PwmSetting.RECOVERY_ACTION, RecoveryAction.class);
    }


    private void forwardUserBasedOnRecoveryMethod(
            final PwmRequest pwmRequest,
            final IdentityVerificationMethod method
    )
            throws ServletException, PwmUnrecoverableException, IOException
    {
        LOGGER.debug(pwmRequest,"attempting to forward request to handle verification method " + method.toString());
        final ForgottenPasswordBean forgottenPasswordBean = forgottenPasswordBean(pwmRequest);
        verifyRequirementsForAuthMethod(pwmRequest,forgottenPasswordBean,method);
        switch (method) {
            case PREVIOUS_AUTH: {
                throw new PwmUnrecoverableException(new ErrorInformation(PwmError.ERROR_UNKNOWN,"previous authentication is required, but user has not previously authenticated"));
            }

            case ATTRIBUTES: {
                pwmRequest.forwardToJsp(PwmConstants.JspUrl.RECOVER_PASSWORD_ATTRIBUTES);
            }
            break;

            case CHALLENGE_RESPONSES: {
                pwmRequest.setAttribute(PwmRequest.Attribute.ForgottenPasswordChallengeSet, forgottenPasswordBean.getPresentableChallengeSet());
                pwmRequest.forwardToJsp(PwmConstants.JspUrl.RECOVER_PASSWORD_RESPONSES);
            }
            break;

            case OTP: {
                final UserInfoBean userInfoBean = readUserInfoBean(pwmRequest, forgottenPasswordBean);
                pwmRequest.setAttribute(PwmRequest.Attribute.ForgottenPasswordUserInfo, userInfoBean);
                pwmRequest.forwardToJsp(PwmConstants.JspUrl.RECOVER_PASSWORD_ENTER_OTP);
            }
            break;

            case TOKEN: {
                final ForgottenPasswordBean.Progress progress = forgottenPasswordBean.getProgress();
                if (progress.getTokenSendChoice() == null) {
                    progress.setTokenSendChoice(figureTokenSendPreference(pwmRequest, forgottenPasswordBean));
                }

                if (progress.getTokenSendChoice() == MessageSendMethod.CHOICE_SMS_EMAIL) {
                    pwmRequest.forwardToJsp(PwmConstants.JspUrl.RECOVER_PASSWORD_TOKEN_CHOICE);
                    return;
                }

                if (!progress.isTokenSent()) {
                    final UserInfoBean userInfoBean = readUserInfoBean(pwmRequest, forgottenPasswordBean);
                    final String destAddress = initializeAndSendToken(pwmRequest, userInfoBean, progress.getTokenSendChoice());
                    progress.setTokenSentAddress(destAddress);
                    progress.setTokenSent(true);
                }

                if (!progress.getSatisfiedMethods().contains(IdentityVerificationMethod.TOKEN)) {
                    pwmRequest.forwardToJsp(PwmConstants.JspUrl.RECOVER_PASSWORD_ENTER_TOKEN);
                    return;
                }
            }
            break;

            case REMOTE_RESPONSES: {
                final UserInfoBean userInfoBean = readUserInfoBean(pwmRequest, forgottenPasswordBean);
                final VerificationMethodSystem remoteMethod;
                if (forgottenPasswordBean.getProgress().getRemoteRecoveryMethod() == null) {
                    remoteMethod = new RemoteVerificationMethod();
                    remoteMethod.init(
                            pwmRequest.getPwmApplication(),
                            userInfoBean,
                            pwmRequest.getSessionLabel(),
                            pwmRequest.getLocale()
                    );
                    forgottenPasswordBean.getProgress().setRemoteRecoveryMethod(remoteMethod);
                } else {
                    remoteMethod = forgottenPasswordBean.getProgress().getRemoteRecoveryMethod();
                }

                final List<VerificationMethodSystem.UserPrompt> prompts = remoteMethod.getCurrentPrompts();
                final String displayInstructions = remoteMethod.getCurrentDisplayInstructions();

                pwmRequest.setAttribute(PwmRequest.Attribute.ForgottenPasswordPrompts, new ArrayList<>(prompts));
                pwmRequest.setAttribute(PwmRequest.Attribute.ForgottenPasswordInstructions, displayInstructions);
                pwmRequest.forwardToJsp(PwmConstants.JspUrl.RECOVER_PASSWORD_REMOTE);
            }
            break;


            case NAAF: {
                final UserInfoBean userInfoBean = readUserInfoBean(pwmRequest, forgottenPasswordBean);
                final VerificationMethodSystem naafMethod;
                if (forgottenPasswordBean.getProgress().getNaafRecoveryMethod() == null) {
                    naafMethod = new PwmNAAFVerificationMethod();
                    naafMethod.init(
                            pwmRequest.getPwmApplication(),
                            userInfoBean,
                            pwmRequest.getSessionLabel(),
                            pwmRequest.getLocale()
                    );
                    forgottenPasswordBean.getProgress().setNaafRecoveryMethod(naafMethod);
                } else {
                    naafMethod = forgottenPasswordBean.getProgress().getNaafRecoveryMethod();
                }

                final List<VerificationMethodSystem.UserPrompt> prompts = naafMethod.getCurrentPrompts();
                final String displayInstructions = naafMethod.getCurrentDisplayInstructions();

                pwmRequest.setAttribute(PwmRequest.Attribute.ForgottenPasswordPrompts, new ArrayList<>(prompts));
                pwmRequest.setAttribute(PwmRequest.Attribute.ForgottenPasswordInstructions, displayInstructions);
                pwmRequest.forwardToJsp(PwmConstants.JspUrl.RECOVER_PASSWORD_NAAF);
            }
            break;

            case OAUTH:
                forgottenPasswordBean.getProgress().setInProgressVerificationMethod(IdentityVerificationMethod.OAUTH);
                final ForgottenPasswordProfile forgottenPasswordProfile = pwmRequest.getConfig().getForgottenPasswordProfiles().get(forgottenPasswordBean.getForgottenPasswordProfileID());
                final OAuthSettings oAuthSettings = OAuthSettings.forForgottenPassword(forgottenPasswordProfile);
                final OAuthMachine oAuthMachine = new OAuthMachine(oAuthSettings);
                pwmRequest.getPwmApplication().getSessionStateService().saveSessionBeans(pwmRequest);
                oAuthMachine.redirectUserToOAuthServer(pwmRequest, null, forgottenPasswordProfile.getIdentifier());
                break;


            default:
                throw new UnsupportedOperationException("unexpected method during forward: " + method.toString());
        }

    }

    private boolean checkAuthRecord(final PwmRequest pwmRequest, final String userGuid)
            throws PwmUnrecoverableException
    {
        if (userGuid == null || userGuid.isEmpty()) {
            return false;
        }

        try {
            final String cookieName = pwmRequest.getConfig().readAppProperty(AppProperty.HTTP_COOKIE_AUTHRECORD_NAME);
            if (cookieName == null || cookieName.isEmpty()) {
                LOGGER.trace(pwmRequest, "skipping auth record cookie read, cookie name parameter is blank");
                return false;
            }

            final AuthenticationFilter.AuthRecord authRecord = pwmRequest.readEncryptedCookie(cookieName, AuthenticationFilter.AuthRecord.class);
            if (authRecord != null) {
                if (authRecord.getGuid() != null && !authRecord.getGuid().isEmpty() && authRecord.getGuid().equals(userGuid)) {
                    LOGGER.debug(pwmRequest, "auth record cookie validated");
                    return true;
                }
            }
        } catch (Exception e) {
            LOGGER.error(pwmRequest, "unexpected error while examining cookie auth record: " + e.getMessage());
        }
        return false;
    }

    protected void forwardToSearchPage(final PwmRequest pwmRequest)
            throws ServletException, PwmUnrecoverableException, IOException
    {
        pwmRequest.addFormInfoToRequestAttr(PwmSetting.FORGOTTEN_PASSWORD_SEARCH_FORM,false,false);
        pwmRequest.forwardToJsp(PwmConstants.JspUrl.RECOVER_PASSWORD_SEARCH);
    }


    private static UserInfoBean readUserInfoBean(final PwmRequest pwmRequest, final ForgottenPasswordBean forgottenPasswordBean) throws PwmUnrecoverableException {
        if (forgottenPasswordBean.getUserIdentity() == null) {
            return null;
        }

        final UserIdentity userIdentity = forgottenPasswordBean.getUserIdentity();

        {
            final UserInfoBean beanInRequest = (UserInfoBean)pwmRequest.getAttribute(PwmRequest.Attribute.ForgottenPasswordUserInfo);
            if (beanInRequest != null) {
                if (userIdentity.equals(beanInRequest.getUserIdentity())) {
                    LOGGER.trace(pwmRequest, "using request cached UserInfoBean");
                    return beanInRequest;
                } else {
                    LOGGER.trace(pwmRequest, "request cached userInfoBean is not for current user, clearing.");
                    pwmRequest.setAttribute(PwmRequest.Attribute.ForgottenPasswordUserInfo, null);
                }
            }
        }

        final ChaiProvider chaiProvider = pwmRequest.getPwmApplication().getProxyChaiProvider(userIdentity.getLdapProfileID());
        final UserStatusReader userStatusReader = new UserStatusReader(pwmRequest.getPwmApplication(), pwmRequest.getSessionLabel());
        final UserInfoBean userInfoBean = new UserInfoBean();
        userStatusReader.populateUserInfoBean(
                userInfoBean,
                pwmRequest.getLocale(),
                userIdentity,
                chaiProvider
        );

        pwmRequest.setAttribute(PwmRequest.Attribute.ForgottenPasswordUserInfo, userInfoBean);

        return userInfoBean;
    }

    private static ResponseSet readResponseSet(final PwmRequest pwmRequest, final ForgottenPasswordBean forgottenPasswordBean)
            throws PwmUnrecoverableException
    {

        if (forgottenPasswordBean.getUserIdentity() == null) {
            return null;
        }

        final PwmApplication pwmApplication = pwmRequest.getPwmApplication();
        final UserIdentity userIdentity = forgottenPasswordBean.getUserIdentity();
        final ResponseSet responseSet;

        try {
            final ChaiUser theUser = pwmApplication.getProxiedChaiUser(userIdentity);
            responseSet = pwmApplication.getCrService().readUserResponseSet(
                    pwmRequest.getSessionLabel(),
                    userIdentity,
                    theUser
            );
        } catch (ChaiUnavailableException e) {
            throw PwmUnrecoverableException.fromChaiException(e);
        }

        return responseSet;
    }
}



