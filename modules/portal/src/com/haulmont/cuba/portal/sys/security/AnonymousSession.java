/*
 * Copyright (c) 2012 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.
 */

package com.haulmont.cuba.portal.sys.security;

import com.haulmont.cuba.portal.security.PortalSession;
import com.haulmont.cuba.security.global.UserSession;

import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Locale;

/**
 * @author artamonov
 * @version $Id$
 */
public class AnonymousSession extends PortalSession {

    public AnonymousSession(UserSession src, @Nullable Locale locale) {
        super(src);
        if (locale != null)
            this.locale = locale;
    }

    @Override
    public void setAddress(String address) {
        this.address = address;
    }

    @Override
    public void setClientInfo(String clientInfo) {
        this.clientInfo = clientInfo;
    }

    @Override
    public void setAttribute(String name, Serializable value) {
        attributes.put(name, value);
    }

    @Override
    public boolean isAuthenticated() {
        return false;
    }

    @Override
    public void setAuthenticated(boolean authenticated) {
    }
}