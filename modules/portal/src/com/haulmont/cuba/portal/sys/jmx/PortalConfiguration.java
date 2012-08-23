/*
 * Copyright (c) 2012 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.
 */

package com.haulmont.cuba.portal.sys.jmx;

import com.haulmont.cuba.core.sys.AppContext;
import org.apache.commons.lang.text.StrBuilder;

import javax.annotation.ManagedBean;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author krivopustov
 * @version $Id$
 */
@ManagedBean("cuba_PortalConfigurationMBean")
public class PortalConfiguration implements PortalConfigurationMBean {

    @Override
    public String printAppProperties() {
        return printAppProperties(null);
    }

    @Override
    public String printAppProperties(@Nullable String prefix) {
        List<String> list = new ArrayList<String>();
        for (String name : AppContext.getPropertyNames()) {
            if (prefix == null || name.startsWith(prefix)) {
                list.add(name + "=" + AppContext.getProperty(name));
            }
        }
        Collections.sort(list);
        return new StrBuilder().appendWithSeparators(list, "\n").toString();
    }

    @Override
    public String getAppProperty(String name) {
        return name + "=" + AppContext.getProperty(name);
    }

    @Override
    public String setAppProperty(String name, String value) {
        AppContext.setProperty(name, value);
        return "Property " + name + " set to " + value;
    }
}
