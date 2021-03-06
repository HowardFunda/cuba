/*
 * Copyright (c) 2008-2017 Haulmont.
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
 */

package com.haulmont.cuba.desktop.sys;

import com.haulmont.cuba.core.sys.EventsImpl;
import com.haulmont.cuba.desktop.App;
import com.haulmont.cuba.gui.events.UiEvent;
import com.haulmont.cuba.gui.events.sys.UiEventsMulticaster;
import org.springframework.context.ApplicationEvent;

import javax.swing.*;

public class DesktopEvents extends EventsImpl {
    @Override
    public void publish(ApplicationEvent event) {
        if (event instanceof UiEvent) {
            if (SwingUtilities.isEventDispatchThread()) {
                UiEventsMulticaster multicaster = App.getInstance().getUiEventsMulticaster();
                multicaster.multicastEvent(event);
            } else {
                throw new IllegalStateException("UiEvent cannot be sent from non-EDT thread");
            }
        } else {
            super.publish(event);
        }
    }
}