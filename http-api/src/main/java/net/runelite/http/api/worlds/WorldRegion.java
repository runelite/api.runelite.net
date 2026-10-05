/*
 * Copyright (c) 2020, melky <https://github.com/melkypie>
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
package net.runelite.http.api.worlds;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Holds the data for each world's region (location)
 */
@AllArgsConstructor
@Getter
public enum WorldRegion
{
	UNITED_STATES_OF_AMERICA(0, "US", "USA"),
	UNITED_KINGDOM(1, "GB", "GBR"),
	AUSTRALIA(3, "AU", "AUS"),
	GERMANY(7, "DE", "DEU"),
	BRAZIL(8, "BR", "BRA"),
	JAPAN(9, "JP", "JPN"),
	SINGAPORE(10, "SG", "SGP"),
	SOUTH_AFRICA(12, "ZA", "ZAF"),
	;

	private final int id;
	/**
	 * ISO-3166-1 alpha-2 country code
	 */
	private final String alpha2;
	/**
	 * ISO-3166-1 alpha-3 country code
	 */
	private final String alpha3;

	/**
	 * Gets the region using the location id
	 * {@link WorldRegion} value.
	 *
	 * @param locationId the location id of world
	 * @return WorldRegion the region of the world
	 */
	public static WorldRegion fromLocationId(int locationId)
	{
		for (WorldRegion region : WorldRegion.values())
		{
			if (region.getId() == locationId)
			{
				return region;
			}
		}
		return null;
	}
}
