/*
 * Copyright (c) 2017-2018, Adam <Adam@sigterm.info>
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
package net.runelite.http.service.item;

import com.google.common.annotations.VisibleForTesting;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.cache.definitions.ItemDefinition;
import net.runelite.http.service.cache.CacheService;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.sql2o.Connection;
import org.sql2o.Query;
import org.sql2o.Sql2o;

@Service
@Slf4j
public class ItemService
{
	private static final String CREATE_ITEMS = "CREATE TABLE IF NOT EXISTS `items` (\n"
		+ "  `id` int(11) NOT NULL,\n"
		+ "  `name` tinytext NOT NULL,\n"
		+ "  `description` tinytext NOT NULL,\n"
		+ "  `type` enum('DEFAULT') NOT NULL,\n"
		+ "  `timestamp` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,\n"
		+ "  PRIMARY KEY (`id`)\n"
		+ ") ENGINE=InnoDB";

	private static final String CREATE_PRICES = "CREATE TABLE IF NOT EXISTS `prices` (\n"
		+ "  `item` int(11) NOT NULL,\n"
		+ "  `price` int(11) NOT NULL,\n"
		+ "  `time` timestamp NOT NULL DEFAULT '0000-00-00 00:00:00',\n"
		+ "  `fetched_time` timestamp NOT NULL DEFAULT '0000-00-00 00:00:00',\n"
		+ "  UNIQUE KEY `item_time` (`item`,`time`),\n"
		+ "  KEY `item_fetched_time` (`item`,`fetched_time`)\n"
		+ ") ENGINE=InnoDB";

	private final Sql2o sql2o;
	private final CacheService cacheService;
	private final OkHttpClient okHttpClient;
	private final HttpUrl digestUrl;

	@Autowired
	public ItemService(
		@Qualifier("Runelite SQL2O") Sql2o sql2o,
		CacheService cacheService,
		OkHttpClient okHttpClient,
		@Value("${runelite.item.digestUrl}") String digestUrl
	)
	{
		this.sql2o = sql2o;
		this.cacheService = cacheService;
		this.okHttpClient = okHttpClient;
		this.digestUrl = HttpUrl.get(digestUrl);

		try (Connection con = sql2o.open())
		{
			con.createQuery(CREATE_ITEMS)
				.executeUpdate();

			con.createQuery(CREATE_PRICES)
				.executeUpdate();
		}
	}

	public List<PriceEntry> fetchPrices()
	{
		try (Connection con = sql2o.beginTransaction())
		{
			Query query = con.createQuery("select t2.item, t3.name, t2.time, prices.price, prices.fetched_time, " +
				" wprices_osrs.high, wprices_osrs.low, wprices_fsw.high as fsw_high, wprices_fsw.low as fsw_low " +
				"  from (select t1.item as item, max(t1.time) as time from prices t1 group by item) t2" +
				"  join prices on t2.item=prices.item and t2.time=prices.time" +
				"  join items t3 on t2.item=t3.id" +
				"  join wiki_prices2 wprices_osrs on t2.item=wprices_osrs.item_id and wprices_osrs.gamemode = 'OSRS'" +
				"  join wiki_prices2 wprices_fsw on t2.item=wprices_fsw.item_id and wprices_fsw.gamemode = 'FSW'"
			);
			return query.executeAndFetch(PriceEntry.class);
		}
	}

	private RSPrices fetchRsPrices() throws IOException
	{
		Request request = new Request.Builder()
			.url(digestUrl)
			.build();

		try (Response response = okHttpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				throw new IOException("Unsuccessful http response: " + response);
			}

			try (final BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream())))
			{
				String header = reader.readLine();
				Instant date = parseHeaderDate(header);
				Map<Integer, Integer> prices = new HashMap<>();

				for (String line; (line = reader.readLine()) != null; )
				{
					line = line.trim();
					if (line.isEmpty())
					{
						continue;
					}

					String[] split = line.split(",");
					int itemId = Integer.parseInt(split[0]);
					int price = Integer.parseInt(split[1]);
					prices.put(itemId, price);
				}

				return new RSPrices(date, prices);
			}
		}
	}

	@VisibleForTesting
	static Instant parseHeaderDate(String header)
	{
		// ID,Current Cost (as of 02-Nov-2022 10:52)
		int l = header.indexOf("as of ");
		String date = header.substring(l + 6, header.length() - 1);
		DateTimeFormatter pattern = new DateTimeFormatterBuilder()
			.appendPattern("dd-MMM-yyyy HH:mm")
			.toFormatter()
			.withZone(ZoneId.of("GMT"));
		return pattern.parse(date, Instant::from);
	}

	@Scheduled(fixedDelay = 1_800_000) // 30 minutes
	public void reloadItems() throws IOException
	{
		List<ItemDefinition> items = cacheService.getItems();
		if (items.isEmpty())
		{
			log.warn("Failed to load any items from cache, item price updating will be disabled");
		}

		// catch any item renames
		insertItems(items);

		RSPrices rsPrices = fetchRsPrices();
		insertPrices(rsPrices);
	}

	private void insertItems(List<ItemDefinition> items)
	{
		try (Connection con = sql2o.beginTransaction())
		{
			int inserted = 0;
			Query query = con.createQuery("insert into items (id, name, description, type) values (:id,"
				+ " :name, :description, :type) ON DUPLICATE KEY UPDATE name = :name,"
				+ " description = :description, type = :type");

			for (ItemDefinition itemDefinition : items)
			{
				if (!itemDefinition.isTradeable())
				{
					continue;
				}

				query
					.addParameter("id", itemDefinition.getId())
					.addParameter("name", itemDefinition.getName())
					// these two are still in the table, but are unused
					.addParameter("description", "unknown")
					.addParameter("type", "DEFAULT")
					.addToBatch();
				++inserted;
			}

			query.executeBatch();
			con.commit(false);

			log.debug("Inserted {} items", inserted);
		}
	}

	private void insertPrices(RSPrices prices)
	{
		try (Connection con = sql2o.beginTransaction())
		{
			Instant now = Instant.now();
			Instant priceTime = prices.date;

			Query query = con.createQuery("insert into prices (item, price, time, fetched_time) values (:item, :price, :time, :fetched_time) "
				+ "ON DUPLICATE KEY UPDATE price = VALUES(price), fetched_time = VALUES(fetched_time)");

			for (Map.Entry<Integer, Integer> entry : prices.prices.entrySet())
			{
				int itemId = entry.getKey();
				int price = entry.getValue(); // gp

				query
					.addParameter("item", itemId)
					.addParameter("price", price)
					.addParameter("time", priceTime)
					.addParameter("fetched_time", now)
					.addToBatch();
			}

			query.executeBatch();
			con.commit(false);

			log.debug("Inserted {} prices", prices.prices.size());
		}
	}
}
