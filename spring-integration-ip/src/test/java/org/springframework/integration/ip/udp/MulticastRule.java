/*
 * Copyright 2015 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.springframework.integration.ip.udp;

import java.net.InetAddress;
import java.net.MulticastSocket;

import org.apache.commons.logging.LogFactory;
import org.junit.Assume;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import org.springframework.integration.ip.util.SocketTestUtils;
import org.springframework.util.Assert;

/**
 * @author Artem Bilan
 * @since 4.3
 */
public class MulticastRule extends TestWatcher {

	public static String GROUP = "225.6.7.8";

	private final String group;

	private final String nic;

	public MulticastRule() {
		this(GROUP);
	}

	public MulticastRule(String group) {
		Assert.hasText(group);
		this.group = group;
		System.setProperty("java.net.preferIPv4Stack", "true");
		System.setProperty("multicast.group", this.group);
		try {
			this.nic = checkMulticast();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
		if (this.nic != null) {
			System.setProperty("multicast.local.address", this.nic);
		}
	}

	private String checkMulticast() throws Exception {
		String nic = SocketTestUtils.chooseANic(true);
		if (nic == null) {	// no multicast support
			return null;
		}
		try {
			MulticastSocket socket = new MulticastSocket();
			socket.joinGroup(InetAddress.getByName(this.group));
			socket.close();
		}
		catch (Exception e) {
			// Ignore. Assume no Multicast - skip the test.
		}
		return nic;
	}

	public String getGroup() {
		return group;
	}

	public String getNic() {
		return nic;
	}

	@Override
	public Statement apply(Statement base, Description description) {
		if (this.nic == null) {
			LogFactory.getLog(this.getClass()).info("No Multicast support; test skipped");
			return new Statement() {

				@Override
				public void evaluate() throws Throwable {
					Assume.assumeTrue(false);
				}
			};
		}
		else {
			return super.apply(base, description);
		}
	}

}
