/*
 * Copyright 2017 the original author or authors.
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

package org.springframework.integration.support;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.integration.context.IntegrationContextUtils;
import org.springframework.integration.core.MessagingTemplate;
import org.springframework.integration.support.channel.BeanFactoryChannelResolver;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.core.DestinationResolver;
import org.springframework.messaging.support.ErrorMessage;
import org.springframework.retry.RecoveryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.util.Assert;

/**
 * A {@link RecoveryCallback} that sends the {@link ErrorMessage}
 * after retry exhaustion.
 * <p>
 * A {@link ErrorMessageStrategy} can be used to provide customization for the
 * target {@link ErrorMessage} based on the {@link RetryContext}.
 *
 * @author Artem Bilan
 *
 * @since 4.3.9
 */
public class ErrorMessagePublishingRecoveryCallback implements RecoveryCallback<Void>, BeanFactoryAware {

	protected final Log logger = LogFactory.getLog(getClass());

	protected final MessagingTemplate messagingTemplate = new MessagingTemplate();

	private DestinationResolver<MessageChannel> channelResolver;

	private MessageChannel recoveryChannel;

	private String recoveryChannelName;

	private ErrorMessageStrategy errorMessageStrategy = new ErrorMessageStrategy() {

		@Override
		public ErrorMessage buildErrorMessage(RetryContext context) {
			return new ErrorMessage(context.getLastThrowable());
		}

	};

	public final void setErrorMessageStrategy(ErrorMessageStrategy errorMessageStrategy) {
		Assert.notNull(errorMessageStrategy, "'errorMessageStrategy' must not be null");
		this.errorMessageStrategy = errorMessageStrategy;
	}

	public final void setRecoveryChannel(MessageChannel recoveryChannel) {
		this.recoveryChannel = recoveryChannel;
	}

	public void setRecoveryChannelName(String recoveryChannelName) {
		this.recoveryChannelName = recoveryChannelName;
	}

	public void setSendTimeout(long sendTimeout) {
		this.messagingTemplate.setSendTimeout(sendTimeout);
	}

	public void setChannelResolver(DestinationResolver<MessageChannel> channelResolver) {
		this.channelResolver = channelResolver;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		Assert.notNull(beanFactory, "beanFactory must not be null");
		if (this.channelResolver == null) {
			this.channelResolver = new BeanFactoryChannelResolver(beanFactory);
		}
	}

	@Override
	public Void recover(RetryContext context) throws Exception {
		populateRecoveryChannel();
		ErrorMessage errorMessage = this.errorMessageStrategy.buildErrorMessage(context);
		if (this.logger.isDebugEnabled() && errorMessage.getPayload() instanceof MessagingException) {
			MessagingException exception = (MessagingException) errorMessage.getPayload();
			this.logger.debug("Sending ErrorMessage: failedMessage: " + exception.getFailedMessage(), exception);
		}
		this.messagingTemplate.send(errorMessage);
		return null;
	}

	private void populateRecoveryChannel() {
		if (this.messagingTemplate.getDefaultDestination() == null) {
			if (this.recoveryChannel == null) {
				String recoveryChannelName = this.recoveryChannelName;
				if (recoveryChannelName == null) {
					recoveryChannelName = IntegrationContextUtils.ERROR_CHANNEL_BEAN_NAME;
				}
				if (this.channelResolver != null) {
					this.recoveryChannel = this.channelResolver.resolveDestination(recoveryChannelName);
				}
			}

			this.messagingTemplate.setDefaultChannel(this.recoveryChannel);
		}
	}

	/**
	 * A strategy to be used on the recovery function to produce
	 * a {@link ErrorMessage} based on the {@link RetryContext}.
	 */
	public interface ErrorMessageStrategy {

		ErrorMessage buildErrorMessage(RetryContext context);

	}

}
