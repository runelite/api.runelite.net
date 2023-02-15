/*
 * Copyright (c) 2019, Adam <Adam@sigterm.info>
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
package net.runelite.http.service.config;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.runelite.http.api.config.ConfigPatch;
import net.runelite.http.api.config.ConfigPatchResult;
import net.runelite.http.api.config.Profile;
import net.runelite.http.api.config.Configuration;
import net.runelite.http.service.account.AuthFilter;
import net.runelite.http.service.account.beans.SessionEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/config")
public class ConfigController
{
	private final ConfigService configService;
	private final AuthFilter authFilter;

	@Autowired
	public ConfigController(ConfigService configService, AuthFilter authFilter)
	{
		this.configService = configService;
		this.authFilter = authFilter;
	}

	@GetMapping("/v2")
	public Map<String, String> get2(HttpServletRequest request, HttpServletResponse response) throws IOException
	{
		SessionEntry session = authFilter.handle(request, response);

		if (session == null)
		{
			return null;
		}

		return configService.getV2(session.getUser());
	}

	@PatchMapping("/v2")
	public List<String> patch(
		HttpServletRequest request,
		HttpServletResponse response,
		@RequestBody ConfigPatch patch
	) throws IOException
	{
		SessionEntry session = authFilter.handle(request, response);
		if (session == null)
		{
			return null;
		}

		List<String> failures = configService.patchV2(session.getUser(), patch);
		if (failures.size() != 0)
		{
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			return failures;
		}

		return null;
	}

	@GetMapping("/v3/list")
	public List<Profile> listProfiles(HttpServletRequest request, HttpServletResponse response) throws IOException
	{
		SessionEntry session = authFilter.handle(request, response);
		if (session == null)
		{
			return null;
		}

		return configService.listProfiles(session.getUser());
	}

	@GetMapping("/v3/{profileId}")
	public Configuration get3(HttpServletRequest request, HttpServletResponse response, @PathVariable long profileId) throws IOException
	{
		SessionEntry session = authFilter.handle(request, response);
		if (session == null)
		{
			return null;
		}

		return configService.getV3(session.getUser(), profileId);
	}

	@PatchMapping("/v3/{profileId}")
	public ConfigPatchResult patch3(
		HttpServletRequest request,
		HttpServletResponse response,
		@PathVariable long profileId,
		@RequestBody ConfigPatch patch
	) throws IOException
	{
		SessionEntry session = authFilter.handle(request, response);
		if (session == null)
		{
			return null;
		}

		ConfigPatchResult result = configService.patchV3(session.getUser(), profileId, patch);
		if (result.getFailures().size() != 0)
		{
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
		}

		return result;
	}

	@PostMapping("/v3/{profileId}/name")
	public void rename3(
		HttpServletRequest request,
		HttpServletResponse response,
		@PathVariable long profileId,
		@RequestBody String name
	) throws IOException
	{
		SessionEntry session = authFilter.handle(request, response);
		if (session == null)
		{
			return;
		}

		if (!configService.renameV3(session.getUser(), profileId, name))
		{
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "profile not found");
		}
	}

	@DeleteMapping("/v3/{profileId}")
	public void delete3(
		HttpServletRequest request,
		HttpServletResponse response,
		@PathVariable long profileId
	) throws IOException
	{
		SessionEntry session = authFilter.handle(request, response);
		if (session == null)
		{
			return;
		}

		if (!configService.deleteV3(session.getUser(), profileId))
		{
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "profile not found");
		}
	}
}
