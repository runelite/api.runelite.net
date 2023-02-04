/*
 * Copyright (c) 2022, Adam <Adam@sigterm.info>
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
package net.runelite.http.service.telemetry;

import com.google.common.base.Strings;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.runelite.http.api.telemetry.Telemetry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/telemetry")
@Slf4j
public class TelemetryController
{
	private final MeterRegistry meterRegistry;
	private final DistributionSummary memoryDistribution;

	@Autowired
	public TelemetryController(MeterRegistry meterRegistry)
	{
		this.meterRegistry = meterRegistry;
		memoryDistribution = DistributionSummary
			.builder("runelite client system memory")
			.minimumExpectedValue(256d)
			.maximumExpectedValue(32768d)
			.publishPercentileHistogram(true)
			.baseUnit("megabytes")
			.register(meterRegistry);
	}

	@PostMapping
	public void post(@RequestBody Telemetry telemetry)
	{
		if (!Strings.isNullOrEmpty(telemetry.getJavaVendor()) && !Strings.isNullOrEmpty(telemetry.getJavaVersion()))
		{
			meterRegistry.counter("runelite client java version",
				"vendor", telemetry.getJavaVendor(),
				"version", telemetry.getJavaVersion())
				.increment();
		}

		if (!Strings.isNullOrEmpty(telemetry.getOsName()) && !Strings.isNullOrEmpty(telemetry.getOsVersion()) && !Strings.isNullOrEmpty(telemetry.getOsArch()))
		{
			meterRegistry.counter("runelite client os version",
				"name", telemetry.getOsName(),
				"version", telemetry.getOsVersion(),
				"arch", telemetry.getOsArch())
				.increment();
		}

		meterRegistry.counter("runelite client launcher version",
			"version", !Strings.isNullOrEmpty(telemetry.getLauncherVersion()) ? telemetry.getLauncherVersion() : "pre1.6")
			.increment();

		if (telemetry.getTotalMemory() > 0L)
		{
			memoryDistribution.record((double) (telemetry.getTotalMemory() / 1024L / 1024L));
		}
	}

	@PostMapping("/error")
	public void error(@RequestParam String type, @RequestParam String error)
	{
		log.info("Client error: {} - {}", type, error);

		meterRegistry.counter("runelite client error",
			"type", type,
			"error", error
		).increment();
	}
}
