/*
 * Copyright (c) 2017-2019, Adam <Adam@sigterm.info>
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

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.not;
import static com.mongodb.client.model.Filters.or;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import static com.mongodb.client.model.Projections.fields;
import static com.mongodb.client.model.Projections.include;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.inc;
import static com.mongodb.client.model.Updates.set;
import static com.mongodb.client.model.Updates.unset;
import com.mongodb.client.result.UpdateResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.http.api.RuneLiteAPI;
import net.runelite.http.api.config.ConfigPatch;
import net.runelite.http.api.config.ConfigPatchResult;
import net.runelite.http.api.config.Profile;
import net.runelite.http.api.config.Configuration;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ConfigService
{
	private static final int MAX_VALUE_LENGTH = 262144;

	private static final long PROFILE_ID_DEFAULT = 0L;
	private static final long PROFILE_ID_RSPROFILE = -1L;

	private static final Profile DEFAULT_PROFILE = new Profile(PROFILE_ID_DEFAULT, "default", 0L);
	private static final Profile RS_PROFILE = new Profile(PROFILE_ID_RSPROFILE, "$rsprofile", 0L);
	private static final Bson INCREMENT_REV = inc("_profile.rev", 1L);

	private final Gson GSON = RuneLiteAPI.GSON;
	private final UpdateOptions upsertUpdateOptions = new UpdateOptions().upsert(true);
	private final FindOneAndUpdateOptions upsertFindAndUpdateOptions = new FindOneAndUpdateOptions()
		.upsert(true)
		.returnDocument(ReturnDocument.AFTER)
		.projection(fields(include("_profile.rev")));

	private final MongoCollection<Document> mongoCollection;

	@Autowired
	public ConfigService(
		MongoClient mongoClient,
		@Value("${mongo.database}") String databaseName
	)
	{

		MongoDatabase database = mongoClient.getDatabase(databaseName);
		this.mongoCollection = database.getCollection("config");

		// Create unique index on (_userId, _profile._id)
		dropIndexV2();
		IndexOptions indexOptions = new IndexOptions().unique(true);
		mongoCollection.createIndex(Indexes.ascending("_userId", "_profile.id"), indexOptions);
	}

	public List<Profile> listProfiles(int userId)
	{
		ArrayList<Profile> profiles = new ArrayList<>(2);
		boolean needMigration = false;

		try (MongoCursor<Document> profileDocs = mongoCollection.find(eq("_userId", userId))
			.projection(fields(include("_profile")))  // mongo will still return a document containing only _id when _profile is undefined
			.iterator()
		)
		{
			while (profileDocs.hasNext())
			{
				Document next = profileDocs.next();
				Document profile = (Document) next.get("_profile");
				if (profile != null)
				{
					profiles.add(new Profile(
						profile.getLong("id"),
						profile.getString("name"),
						profile.getLong("rev")
					));
				}
				else
				{
					needMigration = true;
				}
			}
		}

		if (needMigration)
		{
			Document old = mongoCollection.find(and(eq("_userId", userId), eq("_profile", null))).first();
			if (old == null)
			{
				log.warn("unable to find null profile for user {} to migrate", userId);
				return profiles;
			}

			List<Bson> sets = new ArrayList<>();
			List<Bson> unsets = new ArrayList<>();

			int migrated = 0;
			for (Map.Entry<String, Object> entry : old.entrySet())
			{
				String group = entry.getKey();
				if (entry.getValue() instanceof Document)
				{
					Document valued = (Document) entry.getValue();
					for (Map.Entry<String, Object> entry2 : valued.entrySet())
					{
						String key = entry2.getKey();
						if (key.startsWith("rsprofile:"))
						{
							sets.add(set(group + "." + key, valued.get(key)));
							unsets.add(unset(group + "." + key));
							++migrated;
						}
					}
				}
			}

			log.info("Migrating v2 profile for user ({}) to v3: {} keys", userId, migrated);

			mongoCollection.updateOne(
				profileFilter(userId, PROFILE_ID_RSPROFILE),
				combine(
					set("_profile.id", RS_PROFILE.getId()),
					set("_profile.name", RS_PROFILE.getName()),
					set("_profile.rev", RS_PROFILE.getRev()),
					combine(sets)
				),
				upsertUpdateOptions
			);

			mongoCollection.updateOne(
				profileFilter(userId, null),
				combine(
					set("_profile.id", DEFAULT_PROFILE.getId()),
					set("_profile.name", DEFAULT_PROFILE.getName()),
					set("_profile.rev", DEFAULT_PROFILE.getRev()),
					combine(unsets)
				),
				upsertUpdateOptions
			);

			profiles.add(DEFAULT_PROFILE);
			profiles.add(RS_PROFILE);
		}

		return profiles;
	}

	private Document getAggregateConfigV2(int userId)
	{
		// this is potentially prone to collision problems if the db still contains both a _profile = null and _profile = non-null simultaneously
		// mongo does not provide any ordering capabilities on undefined fields, so we can't do null-first or anything similar
		// it shouldn't happen regardless since v3/list with a _profile = null will convert to _profile = 0
		Bson v2AggregateFilter = and(
			eq("_userId", userId),
			or(
				eq("_profile.id", PROFILE_ID_DEFAULT),
				eq("_profile.id", PROFILE_ID_RSPROFILE),
				eq("_profile", null)
			)
		);

		Document base = new Document();
		mongoCollection.find(v2AggregateFilter)
			.forEach((Consumer<? super Document>) (d -> // mongo has a bad overload here, need explicit cast -.-
			{
				if (d != null)
				{
					merge(d, base);
				}
			}));
		return base;
	}

	private static void merge(Document from, Document to)
	{
		for (Map.Entry<String, Object> entry : from.entrySet())
		{
			String key = entry.getKey();
			Object value = entry.getValue();

			if (value instanceof Document)
			{
				Document d = (Document) to.get(key);
				if (d == null)
				{
					d = new Document();
					to.put(key, d);
				}
				merge((Document) value, d);
			}
			else
			{
				to.put(key, value);
			}
		}
	}

	public Configuration getV3(int userId, long profileId)
	{
		return unpackDbConfig(mongoCollection.find(profileFilter(userId, profileId)).first());
	}

	public Map<String, String> getV2(int userId)
	{
		Configuration configuration = unpackDbConfig(getAggregateConfigV2(userId));
		return configuration.getConfig();
	}

	@Nonnull
	private Configuration unpackDbConfig(Document configDocument)
	{
		if (configDocument == null || configDocument.isEmpty())
		{
			return new Configuration();
		}

		Map<String, String> userConfig = new LinkedHashMap<>();
		for (String group : configDocument.keySet())
		{
			// Reserved keys
			if (group.startsWith("_") || group.startsWith("$"))
			{
				continue;
			}

			Map<String, Object> groupMap = (Map) configDocument.get(group);

			for (Map.Entry<String, Object> entry : groupMap.entrySet())
			{
				String key = entry.getKey();
				Object value = entry.getValue();

				if (value instanceof Map || value instanceof Collection)
				{
					value = GSON.toJson(entry.getValue());
				}
				else if (value == null)
				{
					continue;
				}

				userConfig.put(
					group + "." + key.replace(':', '.'),
					value.toString()
				);
			}
		}

		Configuration configuration = new Configuration();
		configuration.setConfig(userConfig);

		Long rev = configDocument.getEmbedded(Arrays.asList("_profile", "rev"), Long.class);
		if (rev != null)
		{
			configuration.setRev(rev);
		}

		return configuration;
	}

	public ConfigPatchResult patchV3(int userId, long profileId, ConfigPatch patch)
	{
		List<String> failures = new ArrayList<>();
		List<Bson> sets = new ArrayList<>(patch.getEdit().size() + patch.getUnset().size());
		for (Map.Entry<String, String> entry : patch.getEdit().entrySet())
		{
			Bson s = setForKV(entry.getKey(), entry.getValue());
			if (s == null)
			{
				failures.add(entry.getKey());
			}
			else
			{
				sets.add(s);
			}
		}
		for (String key : patch.getUnset())
		{
			Bson s = setForKV(key, null);
			if (s == null)
			{
				failures.add(key);
			}
			else
			{
				sets.add(s);
			}
		}

		if (patch.getProfileName() != null)
		{
			sets.add(set("_profile.name", patch.getProfileName()));
		}

		// always write the profile, even if empty.
		// this is so creating a new profile with no keys yet and setting sync on causes the service to
		// create the profile. If it is not created the client considers it lost and
		// marks it as unsynced when the client restarts next.
		Long rev = null;
		sets.add(INCREMENT_REV);
		Document newRev = mongoCollection.findOneAndUpdate(
			profileFilter(userId, profileId),
			combine(sets),
			upsertFindAndUpdateOptions
		);
		if (newRev != null)
		{
			rev = ((Document) newRev.get("_profile")).getLong("rev");
		}

		return new ConfigPatchResult(rev, failures);
	}

	public List<String> patchV2(int userId, ConfigPatch patch)
	{
		boolean v3Mode = mongoCollection.find(
			and(
				eq("_userId", userId),
				not(eq("_profile", null))
			)
		).first() != null;

		List<String> failures = new ArrayList<>();
		List<Bson> defaultSets = new ArrayList<>(patch.getEdit().size() + patch.getUnset().size());
		List<Bson> rsProfileSets = new ArrayList<>(patch.getEdit().size() + patch.getUnset().size());
		for (Map.Entry<String, String> entry : patch.getEdit().entrySet())
		{
			Bson s = setForKV(entry.getKey(), entry.getValue());
			if (s == null)
			{
				failures.add(entry.getKey());
			}
			else
			{
				List<Bson> targetProfile = v3Mode && isRsProfileKey(entry.getKey()) ? rsProfileSets : defaultSets;
				targetProfile.add(s);
			}
		}
		for (String key : patch.getUnset())
		{
			Bson s = setForKV(key, null);
			if (s == null)
			{
				failures.add(key);
			}
			else
			{
				List<Bson> targetProfile = v3Mode && isRsProfileKey(key) ? rsProfileSets : defaultSets;
				targetProfile.add(s);
			}
		}

		if (defaultSets.size() > 0)
		{
			if (v3Mode)
			{
				defaultSets.add(INCREMENT_REV);
			}

			mongoCollection.updateOne(
				profileFilter(userId, v3Mode ? PROFILE_ID_DEFAULT : null),
				combine(defaultSets),
				upsertUpdateOptions
			);
		}

		if (v3Mode && rsProfileSets.size() > 0)
		{
			rsProfileSets.add(INCREMENT_REV);
			mongoCollection.updateOne(
				profileFilter(userId, PROFILE_ID_RSPROFILE),
				combine(rsProfileSets),
				upsertUpdateOptions
			);
		}

		return failures;
	}

	public boolean renameV3(int userId, long profileId, String name)
	{
		UpdateResult updateResult = mongoCollection.updateOne(
			profileFilter(userId, profileId),
			set("_profile.name", name)
		);
		return updateResult.getModifiedCount() > 0;
	}

	public boolean deleteV3(int userId, long profileId)
	{
		return mongoCollection.deleteOne(profileFilter(userId, profileId)).getDeletedCount() > 0;
	}

	@Nullable
	private Bson setForKV(String key, @Nullable String value)
	{
		if (key.startsWith("$") || key.startsWith("_"))
		{
			return null;
		}

		String[] split = key.split("\\.", 2);
		if (split.length != 2)
		{
			return null;
		}

		String dbKey = split[0] + "." + split[1].replace('.', ':');

		if (Strings.isNullOrEmpty(value))
		{
			return unset(dbKey);
		}

		if (!validateStr(value))
		{
			return null;
		}

		return set(dbKey, value);
	}

	// if it exists, the v2 index ({ _userId : 1 }, { unique : true }) will prevent multiple profiles per-user 
	private void dropIndexV2()
	{
		try (MongoCursor<Document> ixIter = mongoCollection.listIndexes().iterator())
		{
			while (ixIter.hasNext())
			{
				Document ixDoc = ixIter.next();

				Map<String, Object> ixKeys = (Map) ixDoc.get("key");
				if (ixKeys.containsKey("_userId") && !ixKeys.containsKey("_profile.id"))
				{
					String ixName = ixDoc.getString("name");
					log.info("Found v2 index ({}), dropping...", ixName);
					mongoCollection.dropIndex(ixName);
					return;
				}
			}
		}
	}

	private static boolean validateStr(String value)
	{
		return value.length() < MAX_VALUE_LENGTH;
	}

	private static boolean isRsProfileKey(String key)
	{
		int i = key.indexOf('.');
		if (i == -1)
		{
			return false;
		}

		key = key.substring(i + 1);
		return key.startsWith("rsprofile.");
	}

	private static Bson profileFilter(int userId, @Nullable Long profileId)
	{
		return and(
			eq("_userId", userId),
			eq("_profile.id", profileId)
		);
	}
}
