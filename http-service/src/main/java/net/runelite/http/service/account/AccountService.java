/*
 * Copyright (c) 2017, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.http.service.account;

import com.github.scribejava.apis.GoogleApi20;
import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.model.OAuth2AccessToken;
import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.Response;
import com.github.scribejava.core.model.Verb;
import com.github.scribejava.core.oauth.OAuth20Service;
import com.google.common.base.Strings;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.runelite.http.api.RuneLiteAPI;
import net.runelite.http.api.account.OAuthResponse;
import net.runelite.http.service.account.beans.SessionEntry;
import net.runelite.http.service.account.beans.UserEntry;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.sql2o.Connection;
import org.sql2o.Sql2o;
import org.sql2o.Sql2oException;

@RestController
@RequestMapping("/account")
public class AccountService
{
	private static final Logger logger = LoggerFactory.getLogger(AccountService.class);

	private static final String CREATE_SESSIONS = "CREATE TABLE IF NOT EXISTS `sessions` (\n"
		+ "  `user` int(11) NOT NULL PRIMARY KEY,\n"
		+ "  `uuid` varchar(36) NOT NULL,\n"
		+ "  `created` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,\n"
		+ "  `last_used` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,\n"
		+ "  UNIQUE KEY `uuid` (`uuid`),\n"
		+ "  KEY `user` (`user`)\n"
		+ ") ENGINE=InnoDB";

	private static final String CREATE_USERS = "CREATE TABLE IF NOT EXISTS `users` (\n"
		+ "  `id` int(11) NOT NULL AUTO_INCREMENT,\n"
		+ "  `username` tinytext NOT NULL,\n"
		+ "  `created` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,\n"
		+ "  PRIMARY KEY (`id`),\n"
		+ "  UNIQUE KEY `username` (`username`(64))\n"
		+ ") ENGINE=InnoDB";

	private static final String SESSIONS_FK = "ALTER TABLE `sessions`\n"
		+ "  ADD CONSTRAINT `id` FOREIGN KEY (`user`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE CASCADE;";

	private static final String SCOPE = "https://www.googleapis.com/auth/userinfo.email";
	private static final String USERINFO = "https://www.googleapis.com/oauth2/v2/userinfo";

	private final Gson gson = RuneLiteAPI.GSON;

	private final Sql2o sql2o;
	private final String oauthClientId;
	private final String oauthClientSecret;
	private final String oauthCallback;
	private final AuthFilter auth;

	@Autowired
	public AccountService(
		@Qualifier("Runelite SQL2O") Sql2o sql2o,
		@Value("${oauth.client-id}") String oauthClientId,
		@Value("${oauth.client-secret}") String oauthClientSecret,
		@Value("${oauth.callback}") String oauthCallback,
		@Value("${oauth.callback-snapshot}") String oauthSnapshotCallback,
		@Value("#{servletContext.contextPath}") String contextPath,
		AuthFilter auth
	)
	{
		this.sql2o = sql2o;
		this.oauthClientId = oauthClientId;
		this.oauthClientSecret = oauthClientSecret;
		this.auth = auth;

		this.oauthCallback = contextPath.toLowerCase().contains("snapshot") ? oauthSnapshotCallback : oauthCallback;

		try (Connection con = sql2o.open())
		{
			con.createQuery(CREATE_SESSIONS)
				.executeUpdate();

			con.createQuery(CREATE_USERS)
				.executeUpdate();

			try
			{
				con.createQuery(SESSIONS_FK)
					.executeUpdate();
			}
			catch (Sql2oException ex)
			{
				// Ignore, happens when index already exists
			}
		}
	}

	@GetMapping("/login")
	public OAuthResponse login(@RequestParam(required = false) int port, @RequestParam(required = false) String redirectUrl)
	{
		State state = new State();
		state.setPort(port);
		state.setRedirectUrl(redirectUrl);

		OAuth20Service service = new ServiceBuilder()
			.apiKey(oauthClientId)
			.apiSecret(oauthClientSecret)
			.scope(SCOPE)
			.callback(oauthCallback)
			.state(gson.toJson(state))
			.build(GoogleApi20.instance());

		final Map<String, String> additionalParams = new HashMap<>();
		additionalParams.put("prompt", "select_account");

		String authorizationUrl = service.getAuthorizationUrl(additionalParams);

		OAuthResponse lr = new OAuthResponse();
		lr.setOauthUrl(authorizationUrl);
		return lr;
	}

	@GetMapping("/callback")
	public Object callback(
		HttpServletRequest request,
		HttpServletResponse response,
		@RequestParam(required = false) String error,
		@RequestParam String code,
		@RequestParam("state") String stateStr
	) throws InterruptedException, ExecutionException, IOException
	{
		if (error != null)
		{
			logger.info("Error in oauth callback: {}", error);
			return null;
		}

		State state = gson.fromJson(stateStr, State.class);

		logger.debug("Got authorization code {}", code);

		OAuth20Service service = new ServiceBuilder()
			.apiKey(oauthClientId)
			.apiSecret(oauthClientSecret)
			.scope(SCOPE)
			.callback(oauthCallback)
			.state(gson.toJson(state))
			.build(GoogleApi20.instance());

		OAuth2AccessToken accessToken = service.getAccessToken(code);

		// Access user info
		OAuthRequest orequest = new OAuthRequest(Verb.GET, USERINFO);
		service.signRequest(accessToken, orequest);

		Response oresponse = service.execute(orequest);

		if (oresponse.getCode() / 100 != 2)
		{
			// Could be a forged result
			return null;
		}

		UserInfo userInfo = gson.fromJson(oresponse.getBody(), UserInfo.class);

		logger.info("Got user info: {}", userInfo);

		// secret session id
		final UUID uuid = UUID.randomUUID();

		try (Connection con = sql2o.open())
		{
			con.createQuery("insert ignore into users (username) values (:username)")
				.addParameter("username", userInfo.getEmail())
				.executeUpdate();

			UserEntry user = con.createQuery("select id from users where username = :username")
				.addParameter("username", userInfo.getEmail())
				.executeAndFetchFirst(UserEntry.class);

			if (user == null)
			{
				logger.warn("Unable to find newly created user session");
				return null; // that's weird
			}

			// insert session
			con.createQuery("insert ignore into sessions (user, uuid) values (:user, :uuid)")
				.addParameter("user", user.getId())
				.addParameter("uuid", uuid.toString())
				.executeUpdate();

			logger.info("Created session for user {}", userInfo.getEmail());
		}

		HttpUrl redir;
		if (!Strings.isNullOrEmpty(state.getRedirectUrl()))
		{
			// redirect to website
			redir = HttpUrl.get(state.getRedirectUrl())
				.newBuilder()
				.addQueryParameter("username", userInfo.getEmail())
				.addQueryParameter("sessionId", uuid.toString())
				.build();
		}
		else
		{
			// redirect to client
			redir = new HttpUrl.Builder()
				.scheme("http")
				.host("localhost")
				.port(state.getPort())
				.addPathSegment("oauth")
				.addQueryParameter("username", userInfo.getEmail())
				.addQueryParameter("sessionId", uuid.toString())
				.build();
		}
		response.sendRedirect(redir.toString());

		return "";
	}

	@GetMapping("/logout")
	public void logout(HttpServletRequest request, HttpServletResponse response) throws IOException
	{
		SessionEntry session = auth.handle(request, response);

		if (session == null)
		{
			return;
		}

		auth.invalidate(session.getUuid());

		try (Connection con = sql2o.open())
		{
			con.createQuery("delete from sessions where uuid = :uuid")
				.addParameter("uuid", session.getUuid().toString())
				.executeUpdate();
		}
	}

	@GetMapping("/session-check")
	public void sessionCheck(HttpServletRequest request, HttpServletResponse response) throws IOException
	{
		auth.handle(request, response);
	}
}
