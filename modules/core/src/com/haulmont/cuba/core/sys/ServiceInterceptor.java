/*
 * Copyright (c) 2008-2016 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.haulmont.cuba.core.sys;

import com.haulmont.cuba.core.Persistence;
import com.haulmont.cuba.core.app.MiddlewareStatisticsAccumulator;
import com.haulmont.cuba.core.app.ServerConfig;
import com.haulmont.cuba.core.global.*;
import com.haulmont.cuba.core.global.validation.MethodParametersValidationException;
import com.haulmont.cuba.core.global.validation.MethodResultValidationException;
import com.haulmont.cuba.core.global.validation.ServiceMethodConstraintViolation;
import com.haulmont.cuba.core.global.validation.groups.ServiceParametersChecks;
import com.haulmont.cuba.core.global.validation.groups.ServiceResultChecks;
import com.haulmont.cuba.core.sys.remoting.RemoteClientInfo;
import com.haulmont.cuba.security.app.UserSessionsAPI;
import com.haulmont.cuba.security.global.NoUserSessionException;
import com.haulmont.cuba.security.global.TrustedAccessRequiredException;
import com.haulmont.cuba.security.global.TrustedClientOnly;
import com.haulmont.cuba.security.global.UserSession;
import com.haulmont.cuba.security.sys.TrustedLoginHandler;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.MethodInvocationProceedingJoinPoint;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.validation.annotation.Validated;

import javax.annotation.Nullable;
import javax.validation.ConstraintViolation;
import javax.validation.executable.ExecutableValidator;
import javax.validation.groups.Default;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Intercepts invocations of the middleware services.
 * <br> Checks {@link UserSession} validity and wraps exceptions into {@link RemoteException}.
 */
public class ServiceInterceptor {
    private static final Logger log = LoggerFactory.getLogger(ServiceInterceptor.class);

    private UserSessionsAPI userSessions;

    private Persistence persistence;

    private BeanValidation beanValidation;

    private MiddlewareStatisticsAccumulator statisticsAccumulator;

    private TrustedLoginHandler trustedLoginHandler;

    private boolean checkServiceCallFromTrustedIp = false;

    boolean logInternalServiceInvocation;

    public void setUserSessions(UserSessionsAPI userSessions) {
        this.userSessions = userSessions;
    }

    public void setPersistence(Persistence persistence) {
        this.persistence = persistence;
    }

    public void setBeanValidation(BeanValidation beanValidation) {
        this.beanValidation = beanValidation;
    }

    public void setStatisticsAccumulator(MiddlewareStatisticsAccumulator statisticsAccumulator) {
        this.statisticsAccumulator = statisticsAccumulator;
    }

    public void setConfiguration(Configuration configuration) {
        ServerConfig serverConfig = configuration.getConfig(ServerConfig.class);

        this.logInternalServiceInvocation = serverConfig.getLogInternalServiceInvocation();
        this.checkServiceCallFromTrustedIp = serverConfig.getCheckServiceCallFromTrustedIp();
    }

    public void setTrustedLoginHandler(TrustedLoginHandler trustedLoginHandler) {
        this.trustedLoginHandler = trustedLoginHandler;
    }

    private Object aroundInvoke(ProceedingJoinPoint ctx) throws Throwable {
        SecurityContext securityContext = AppContext.getSecurityContextNN();
        boolean internalInvocation = securityContext.incServiceInvocation() > 0;
        try {
            if (internalInvocation) {
                if (logInternalServiceInvocation) {
                    log.warn("Invoking '{}' from another service", ctx.getSignature());
                }

                ValidateServiceMethodContext validatedContext = getValidateServiceMethodContext(ctx);
                validateMethodParameters(ctx, validatedContext);

                Object res = ctx.proceed();

                validateMethodResult(ctx, validatedContext, res);

                return res;
            } else {
                statisticsAccumulator.incMiddlewareRequestsCount();
                try {
                    // check if remote access from trusted environment
                    if (checkServiceCallFromTrustedIp) {
                        checkTrustedAccess(ctx);
                    }

                    // Using UserSessionsAPI directly to make sure the session's "last used" timestamp is propagated to the cluster
                    UserSession userSession = userSessions.getAndRefresh(securityContext.getSessionId(), true);
                    if (userSession == null) {
                        throw new NoUserSessionException(securityContext.getSessionId());
                    }

                    ValidateServiceMethodContext validatedContext = getValidateServiceMethodContext(ctx);
                    validateMethodParameters(ctx, validatedContext);

                    boolean checkTransactionOnExit = Stores.getAdditional().isEmpty() && !persistence.isInTransaction();

                    log.trace("Invoking: {}, session={}", ctx.getSignature(), userSession);

                    Object res = ctx.proceed();

                    validateMethodResult(ctx, validatedContext, res);

                    if (checkTransactionOnExit && persistence.isInTransaction()) {
                        log.warn("Open transaction left in {}", ctx.getSignature().toShortString());
                    }

                    return res;
                } catch (Throwable e) {
                    logException(e, ctx);
                    // Propagate the special exception to avoid serialization errors on remote clients
                    throw new RemoteException(e);
                }
            }
        } finally {
            securityContext.decServiceInvocation();
        }
    }

    /**
     * Check if the client is permitted to call the service method.
     *
     * @param ctx context
     */
    protected void checkTrustedAccess(ProceedingJoinPoint ctx) {
        RemoteClientInfo remoteClientInfo = RemoteClientInfo.get();

        if (remoteClientInfo != null
                && ctx instanceof MethodInvocationProceedingJoinPoint) {
            MethodSignature signature = (MethodSignature) ctx.getSignature();

            if (signature.getMethod().getAnnotation(TrustedClientOnly.class) != null
                    || signature.getClass().getAnnotation(TrustedClientOnly.class) != null) {

                if (!trustedLoginHandler.checkAddress(remoteClientInfo.getAddress())) {
                    log.warn("Client is not allowed to call '{}' since IP '{}' is not trusted",
                            ctx.getSignature().toShortString(),
                            remoteClientInfo.getAddress());

                    throw new TrustedAccessRequiredException();
                }
            }
        }
    }

    @Nullable
    protected ValidateServiceMethodContext getValidateServiceMethodContext(ProceedingJoinPoint ctx) {
        ValidateServiceMethodContext validatedContext = null;
        if (ctx instanceof MethodInvocationProceedingJoinPoint) {
            MethodInvocationProceedingJoinPoint methodInvocationCtx = (MethodInvocationProceedingJoinPoint) ctx;

            Method method = ((MethodSignature) ctx.getSignature()).getMethod();

            Validated validated = getValidated(method, ctx.getSignature().getDeclaringType());
            if (validated != null) {
                Object[] args = methodInvocationCtx.getArgs();
                ExecutableValidator validator = beanValidation.getValidator().forExecutables();

                validatedContext = new ValidateServiceMethodContext(validator, ctx.getThis(),
                        method, args, validated.value());
            }
        }
        return validatedContext;
    }

    protected void validateMethodParameters(ProceedingJoinPoint ctx,
                                            @Nullable ValidateServiceMethodContext validatedContext) {
        if (validatedContext != null) {
            log.trace("Validating service call params: {}", ctx.getSignature());

            ExecutableValidator validator = validatedContext.getValidator();

            Class[] constraintGroups = validatedContext.getGroups();
            if (constraintGroups.length == 0) {
                constraintGroups = new Class[]{Default.class, ServiceParametersChecks.class};
            }

            Set<ConstraintViolation<Object>> violations = validator.validateParameters(
                    validatedContext.getTarget(),
                    validatedContext.getMethod(),
                    validatedContext.getArgs(),
                    constraintGroups);

            if (!violations.isEmpty()) {
                Class serviceInterface = ctx.getSignature().getDeclaringType();
                Set<ConstraintViolation<Object>> resultViolations = violations.stream()
                        .map(violation -> new ServiceMethodConstraintViolation(serviceInterface, violation))
                        .collect(Collectors.toSet());

                throw new MethodParametersValidationException("Service method parameters validation failed", resultViolations);
            }
        }
    }

    protected void validateMethodResult(ProceedingJoinPoint ctx, ValidateServiceMethodContext validatedContext,
                                        Object methodResult) {
        if (validatedContext != null) {
            ExecutableValidator validator = validatedContext.getValidator();

            log.trace("Validating service call result: {}", ctx.getSignature());

            Class[] constraintGroups = validatedContext.getGroups();
            if (constraintGroups.length == 0) {
                constraintGroups = new Class[]{Default.class, ServiceResultChecks.class};
            }

            Set<ConstraintViolation<Object>> violations = validator.validateReturnValue(
                    validatedContext.getTarget(),
                    validatedContext.getMethod(),
                    methodResult,
                    constraintGroups);

            if (!violations.isEmpty()) {
                Class serviceInterface = ctx.getSignature().getDeclaringType();
                Set<ConstraintViolation<Object>> paramsViolations = violations.stream()
                        .map(violation -> new ServiceMethodConstraintViolation(serviceInterface, violation))
                        .collect(Collectors.toSet());

                throw new MethodResultValidationException("Service method result validation failed", paramsViolations);
            }
        }
    }

    protected void logException(Throwable e, ProceedingJoinPoint ctx) {
        if (e instanceof NoUserSessionException) {
            // If you don't want NoUserSessionException in log, set level higher than INFO for ServiceInterceptor logger
            log.info("Exception in {}: {}", ctx.getSignature().toShortString(), e.toString());
        } else if (e instanceof MethodParametersValidationException) {
            log.info("MethodParametersValidationException in {}: {}, violations:\n{}", ctx.getSignature().toShortString(), e.toString(),
                    ((MethodParametersValidationException) e).getConstraintViolations());
        } else if (e instanceof MethodResultValidationException) {
            log.error("MethodResultValidationException in {}: {}, violations:\n{}", ctx.getSignature().toShortString(), e.toString(),
                     ((MethodResultValidationException) e).getConstraintViolations());
        } else {
            Logging annotation = e.getClass().getAnnotation(Logging.class);
            if (annotation == null || annotation.value() == Logging.Type.FULL) {
                log.error("Exception: ", e);
            } else if (annotation.value() == Logging.Type.BRIEF) {
                log.error("Exception in {}: {}", ctx.getSignature().toShortString(), e.toString());
            }
        }
    }

    protected Validated getValidated(Method method, Class targetClass) {
        Validated validatedAnn = AnnotationUtils.findAnnotation(method, Validated.class);
        if (validatedAnn == null) {
            validatedAnn = AnnotationUtils.findAnnotation(targetClass, Validated.class);
        }
        return validatedAnn;
    }

    protected static class ValidateServiceMethodContext {
        protected ExecutableValidator validator;
        protected Object target;
        protected Method method;
        protected Object[] args;
        protected Class[] groups;

        public ValidateServiceMethodContext() {
        }

        public ValidateServiceMethodContext(ExecutableValidator validator, Object target, Method method, Object[] args,  Class[] groups) {
            this.validator = validator;
            this.target = target;
            this.method = method;
            this.args = args;
            this.groups = groups;
        }

        public ExecutableValidator getValidator() {
            return validator;
        }

        public void setValidator(ExecutableValidator validator) {
            this.validator = validator;
        }

        public Object getTarget() {
            return target;
        }

        public void setTarget(Object target) {
            this.target = target;
        }

        public Method getMethod() {
            return method;
        }

        public void setMethod(Method method) {
            this.method = method;
        }

        public Class[] getGroups() {
            return groups;
        }

        public void setGroups(Class[] groups) {
            this.groups = groups;
        }

        public Object[] getArgs() {
            return args;
        }

        public void setArgs(Object[] args) {
            this.args = args;
        }
    }
}