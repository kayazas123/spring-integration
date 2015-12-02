/*
 * Copyright 2002-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.integration.ip.udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.logging.LogFactory;
import org.junit.Test;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.handler.ServiceActivatingHandler;
import org.springframework.integration.ip.IpHeaders;
import org.springframework.integration.ip.util.SocketTestUtils;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.integration.test.util.SocketUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.SubscribableChannel;

/**
 *
 * @author Gary Russell
 * @author Artem Bilan
 * @since 2.0
 *
 */
public class UdpChannelAdapterTests {

	@Test
	public void testUnicastReceiver() throws Exception {
		testUnicastReceiver(false, false);
	}

	@Test
	public void testUnicastReceiverLocalNicOnly() throws Exception {
		testUnicastReceiver(false, true);
	}

	@Test
	public void testUnicastReceiverDeadExecutor() throws Exception {
		testUnicastReceiver(true, false);
	}

	private void testUnicastReceiver(final boolean killExecutor, boolean useLocalAddress) throws Exception {
		QueueChannel channel = new QueueChannel(2);
		final CountDownLatch stopLatch = new CountDownLatch(1);
		final CountDownLatch exitLatch = new CountDownLatch(1);
		final AtomicBoolean stopping = new AtomicBoolean();
		final AtomicReference<Exception> exceptionHolder = new AtomicReference<Exception>();
		UnicastReceivingChannelAdapter adapter = new UnicastReceivingChannelAdapter(0) {

			@Override
			public boolean isActive() {
				if (stopping.get()) {
					try {
						stopLatch.await(10, TimeUnit.SECONDS);
					}
					catch (InterruptedException e) {
						fail();
					}
					return true;
				}
				else {
					return super.isActive();
				}
			}

			@Override
			protected DatagramPacket receive() throws Exception {
				if (stopping.get()) {
					return new DatagramPacket(new byte[0], 0);
				}
				else {
					return super.receive();
				}
			}

			@Override
			protected boolean asyncSendMessage(DatagramPacket packet) {
				boolean result = false;
				try {
					result = super.asyncSendMessage(packet);
				}
				catch (Exception e) {
					exceptionHolder.set(e);
				}
				if (stopping.get()) {
					exitLatch.countDown();
				}
				return result;
			}

			@Override
			public Executor getTaskExecutor() {
				Executor taskExecutor = super.getTaskExecutor();
				if (killExecutor && taskExecutor != null) {
					((ExecutorService) taskExecutor).shutdown();
				}
				return taskExecutor;
			}

		};
		adapter.setOutputChannel(channel);
		if (useLocalAddress) {
			adapter.setLocalAddress("127.0.0.1");
		}
		adapter.start();
		SocketTestUtils.waitListening(adapter);
		int port = adapter.getPort();

		Message<byte[]> message = MessageBuilder.withPayload("ABCD".getBytes()).build();
		DatagramPacketMessageMapper mapper = new DatagramPacketMessageMapper();
		DatagramPacket packet = mapper.fromMessage(message);
		packet.setSocketAddress(new InetSocketAddress("localhost", port));
		DatagramSocket datagramSocket = new DatagramSocket(SocketUtils.findAvailableUdpSocket());
		datagramSocket.send(packet);
		datagramSocket.close();
		@SuppressWarnings("unchecked")
		Message<byte[]> receivedMessage = (Message<byte[]>) channel.receive(10000);
		assertNotNull(receivedMessage);
		assertEquals(new String(message.getPayload()), new String(receivedMessage.getPayload()));
		stopping.set(true);
		adapter.stop();
		stopLatch.countDown();
		exitLatch.await(10, TimeUnit.SECONDS);
		// Previously it failed with NPE
		assertNull(exceptionHolder.get());
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testUnicastReceiverWithReply() throws Exception {
		QueueChannel channel = new QueueChannel(2);
		UnicastReceivingChannelAdapter adapter = new UnicastReceivingChannelAdapter(0);
		adapter.setOutputChannel(channel);
		adapter.start();
		SocketTestUtils.waitListening(adapter);
		int port = adapter.getPort();

		Message<byte[]> message = MessageBuilder.withPayload("ABCD".getBytes()).build();
		DatagramPacketMessageMapper mapper = new DatagramPacketMessageMapper();
		DatagramPacket packet = mapper.fromMessage(message);
		packet.setSocketAddress(new InetSocketAddress("localhost", port));
		final DatagramSocket socket = new DatagramSocket(SocketUtils.findAvailableUdpSocket());
		socket.send(packet);
		final AtomicReference<DatagramPacket> theAnswer = new AtomicReference<DatagramPacket>();
		final CountDownLatch receiverReadyLatch = new CountDownLatch(1);
		final CountDownLatch replyReceivedLatch = new CountDownLatch(1);
		//main thread sends the reply using the headers, this thread will receive it
		Executors.newSingleThreadExecutor().execute(new Runnable() {
			@Override
			public void run() {
				DatagramPacket answer = new DatagramPacket(new byte[2000], 2000);
				try {
					receiverReadyLatch.countDown();
					socket.receive(answer);
					theAnswer.set(answer);
					replyReceivedLatch.countDown();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		});
		Message<byte[]> receivedMessage = (Message<byte[]>) channel.receive(2000);
		assertEquals(new String(message.getPayload()), new String(receivedMessage.getPayload()));
		String replyString = "reply:" + System.currentTimeMillis();
		byte[] replyBytes = replyString.getBytes();
		DatagramPacket reply = new DatagramPacket(replyBytes, replyBytes.length);
		reply.setSocketAddress(new InetSocketAddress(
				(String) receivedMessage.getHeaders().get(IpHeaders.IP_ADDRESS),
				(Integer) receivedMessage.getHeaders().get(IpHeaders.PORT)));
		assertTrue(receiverReadyLatch.await(10, TimeUnit.SECONDS));
		DatagramSocket datagramSocket = new DatagramSocket();
		datagramSocket.send(reply);
		assertTrue(replyReceivedLatch.await(10, TimeUnit.SECONDS));
		DatagramPacket answerPacket = theAnswer.get();
		assertNotNull(answerPacket);
		assertEquals(replyString, new String(answerPacket.getData(), 0, answerPacket.getLength()));
		datagramSocket.close();
		socket.close();
		adapter.stop();
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testUnicastSender() throws Exception {
		QueueChannel channel = new QueueChannel(2);
		UnicastReceivingChannelAdapter adapter = new UnicastReceivingChannelAdapter(0);
		adapter.setBeanName("test");
		adapter.setOutputChannel(channel);
//		SocketUtils.setLocalNicIfPossible(adapter);
		adapter.start();
		SocketTestUtils.waitListening(adapter);
		int port = adapter.getPort();

//		String whichNic = SocketUtils.chooseANic(false);
		UnicastSendingMessageHandler handler = new UnicastSendingMessageHandler(
				"localhost", port, false, true,
				"localhost",
//				whichNic,
				SocketUtils.findAvailableUdpSocket(), 5000);
//		handler.setLocalAddress(whichNic);
		handler.setBeanFactory(mock(BeanFactory.class));
		handler.afterPropertiesSet();
		handler.start();
		Message<byte[]> message = MessageBuilder.withPayload("ABCD".getBytes()).build();
		handler.handleMessage(message);
		Message<byte[]> receivedMessage = (Message<byte[]>) channel.receive(2000);
		assertEquals(new String(message.getPayload()), new String(receivedMessage.getPayload()));
		adapter.stop();
		handler.stop();
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testMulticastReceiver() throws Exception {
		System.setProperty("java.net.preferIPv4Stack", "true");
		QueueChannel channel = new QueueChannel(2);
		MulticastReceivingChannelAdapter adapter = new MulticastReceivingChannelAdapter("225.6.7.8", 0);
		adapter.setOutputChannel(channel);
		String nic = checkMulticast();
		if (nic == null) {
			return;
		}
		adapter.setLocalAddress(nic);
		adapter.start();
		SocketTestUtils.waitListening(adapter);
		int port = adapter.getPort();

		Message<byte[]> message = MessageBuilder.withPayload("ABCD".getBytes()).build();
		DatagramPacketMessageMapper mapper = new DatagramPacketMessageMapper();
		DatagramPacket packet = mapper.fromMessage(message);
		packet.setSocketAddress(new InetSocketAddress("225.6.7.8", port));
		DatagramSocket datagramSocket = new DatagramSocket(0, Inet4Address.getByName(nic));
		datagramSocket.send(packet);
		datagramSocket.close();

		Message<byte[]> receivedMessage = (Message<byte[]>) channel.receive(2000);
		assertNotNull(receivedMessage);
		assertEquals(new String(message.getPayload()), new String(receivedMessage.getPayload()));
		adapter.stop();
	}

	private String checkMulticast() throws Exception {
		String nic = SocketTestUtils.chooseANic(true);
		if (nic == null) {	// no multicast support
			LogFactory.getLog(this.getClass()).info("No Multicast support; test skipped");
			return null;
		}
		try {
			MulticastSocket socket = new MulticastSocket();
			socket.joinGroup(InetAddress.getByName("225.6.7.9"));
			socket.close();
		}
		catch (Exception e) {
			LogFactory.getLog(this.getClass()).info("No Multicast support; test skipped");
		}
		return nic;
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testMulticastSender() throws Exception {
		System.setProperty("java.net.preferIPv4Stack", "true");
		QueueChannel channel = new QueueChannel(2);
		int port = SocketUtils.findAvailableUdpSocket();
		UnicastReceivingChannelAdapter adapter = new MulticastReceivingChannelAdapter("225.6.7.9", port);
		adapter.setOutputChannel(channel);
		String nic = checkMulticast();
		if (nic == null) {
			return;
		}
		adapter.setLocalAddress(nic);
		adapter.start();
		SocketTestUtils.waitListening(adapter);

		MulticastSendingMessageHandler handler = new MulticastSendingMessageHandler("225.6.7.9", port);
		handler.setLocalAddress(nic);
		Message<byte[]> message = MessageBuilder.withPayload("ABCD".getBytes()).build();
		handler.handleMessage(message);

		Message<byte[]> receivedMessage = (Message<byte[]>) channel.receive(2000);
		assertNotNull(receivedMessage);
		assertEquals(new String(message.getPayload()), new String(receivedMessage.getPayload()));
		adapter.stop();
	}

	@Test
	public void testUnicastReceiverException() throws Exception {
		SubscribableChannel channel = new DirectChannel();
		UnicastReceivingChannelAdapter adapter = new UnicastReceivingChannelAdapter(0);
		adapter.setOutputChannel(channel);
//		SocketUtils.setLocalNicIfPossible(adapter);
		adapter.setOutputChannel(channel);
		ServiceActivatingHandler handler = new ServiceActivatingHandler(new FailingService());
		channel.subscribe(handler);
		QueueChannel errorChannel = new QueueChannel();
		adapter.setErrorChannel(errorChannel);
		adapter.start();
		SocketTestUtils.waitListening(adapter);
		int port = adapter.getPort();

		Message<byte[]> message = MessageBuilder.withPayload("ABCD".getBytes()).build();
		DatagramPacketMessageMapper mapper = new DatagramPacketMessageMapper();
		DatagramPacket packet = mapper.fromMessage(message);
		packet.setSocketAddress(new InetSocketAddress("localhost", port));
		DatagramSocket datagramSocket = new DatagramSocket(SocketUtils.findAvailableUdpSocket());
		datagramSocket.send(packet);
		datagramSocket.close();
		Message<?> receivedMessage = errorChannel.receive(2000);
		assertNotNull(receivedMessage);
		assertEquals("Failed", ((Exception) receivedMessage.getPayload()).getCause().getMessage());
		adapter.stop();
	}

	@Test
	public void testSocketExpression() throws Exception {
		ConfigurableApplicationContext context = new ClassPathXmlApplicationContext("socket-expression-context.xml",
				this.getClass());
		UnicastSendingMessageHandler outbound = context.getBean(UnicastSendingMessageHandler.class);
		outbound.setSocketExpressionString("@inbound.socket");
		UnicastReceivingChannelAdapter inbound = context.getBean(UnicastReceivingChannelAdapter.class);
		int port = inbound.getPort();
		DatagramPacket packet = new DatagramPacket("foo".getBytes(), 3);
		packet.setSocketAddress(new InetSocketAddress("localhost", port));
		DatagramSocket socket = new DatagramSocket();
		socket.send(packet);
		socket.receive(packet);
		assertEquals("FOO", new String(packet.getData()));
		context.close();
	}

	private class FailingService {
		@SuppressWarnings("unused")
		public String serviceMethod(byte[] bytes) {
			throw new RuntimeException("Failed");
		}
	}


}
