/**
 * Copyright © 2016-2020 The Thingsboard Authors
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
package org.thingsboard.rule.engine.api;

import org.thingsboard.server.common.data.exception.ThingsboardException;

import com.fasterxml.jackson.databind.JsonNode;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;

public interface MailService {

    void updateMailConfiguration();

    void sendEmail(String email, String subject, String message) throws ThingsboardException;
    
    void sendTestMail(JsonNode config, String email) throws ThingsboardException;
    
    void sendActivationEmail(String activationLink, String email) throws ThingsboardException;
    
    void sendAccountActivatedEmail(String loginLink, String email) throws ThingsboardException;
    
    void sendResetPasswordEmail(String passwordResetLink, String email) throws ThingsboardException;
    
    void sendPasswordWasResetEmail(String loginLink, String email) throws ThingsboardException;

    void send(String from, String to, String cc, String bcc, String subject, String body) throws MessagingException;
}
