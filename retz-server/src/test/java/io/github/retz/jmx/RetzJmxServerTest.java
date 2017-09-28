/**
 *    Retz
 *    Copyright (C) 2016-2017 Nautilus Technologies, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package io.github.retz.jmx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.j256.simplejmx.client.JmxClient;
import io.github.retz.admin.AdminConsoleClient;
import io.github.retz.cli.FileConfiguration;
import io.github.retz.db.Database;
import io.github.retz.protocol.data.User;
import io.github.retz.scheduler.Launcher;
import io.github.retz.scheduler.ServerConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.management.ObjectName;
import java.io.InputStream;
import java.util.*;

import static org.junit.Assert.*;

public class RetzJmxServerTest {
    private int port;

    @Before
    public void before() throws Exception {
        InputStream in = Launcher.class.getResourceAsStream("/retz-tls.properties");

        ServerConfiguration config = new ServerConfiguration(in);
        Database.getInstance().init(config);
        RetzJmxServer.start(config);
        port = config.getJmxPort();
    }

    @After
    public void after() throws Exception {
        RetzJmxServer.stop();
        Database.getInstance().clear();
        Database.getInstance().stop();
    }

    @Test
    public void smoke() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new Jdk8Module());
        JmxClient jmxClient = new JmxClient("localhost", port);

        try (AdminConsoleClient client = new AdminConsoleClient(jmxClient)) {
            List<String> users = client.listUser();
            assertFalse(users.isEmpty());
            assertEquals(1, users.size());
            assertEquals("deadbeef", users.get(0));
            {
                String u = client.getUser(users.get(0));
                User user = client.getUserAsObject(users.get(0));
                assertNotNull(user);
                assertTrue(user.enabled());
                assertEquals(u, mapper.writeValueAsString(user));
            }

            {
                User user = client.createUserAsObject("@nushio");
                assertNotNull(user);
                assertTrue(user.enabled());
                System.err.println("User created: " + mapper.writeValueAsString(user));
                System.err.println(FileConfiguration.userAsConfig(user));
            }

            {
                System.err.println(client.enableUser("deadbeef", false));
                User user = client.getUserAsObject("deadbeef");
                assertNotNull(user);
                assertFalse(user.enabled());
            }

            {
                assertNotEquals(0, jmxClient.getAttributesInfo(new ObjectName("io.github.retz.db:type=TomcatThreadPool")).length);
                assertNotEquals(0, jmxClient.getAttributesInfo(new ObjectName("io.github.retz:type=Stats,name=Status")).length);
                assertNotEquals(0, jmxClient.getAttributesInfo(new ObjectName("io.github.retz:type=Stats,name=TotalOffered")).length);
                assertNotEquals(0, jmxClient.getAttributesInfo(new ObjectName("io.github.retz:type=Stats,name=TotalUsed")).length);
            }
        }
    }
}
