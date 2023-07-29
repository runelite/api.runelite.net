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
package net.runelite.http.service.cache;

import lombok.RequiredArgsConstructor;
import net.runelite.http.service.cache.beans.ArchiveEntry;
import net.runelite.http.service.cache.beans.CacheEntry;
import org.sql2o.Connection;

@RequiredArgsConstructor
class CacheDAO
{
	private final Connection conn;

	CacheEntry findMostRecent()
	{
		return conn.createQuery("select id, revision, date from cache order by revision desc, date desc limit 1")
			.executeAndFetchFirst(CacheEntry.class);
	}

	ArchiveEntry findArchive(CacheEntry cacheEntry, int index, int archive)
	{
		return conn.createQuery("select a.id, a.index, a.archive, a.crc, a.name, a.revision, a.data_id from cache_archive ca " +
			"join archive a on ca.archive_id = a.id where ca.cache_id = :cache_id and a.index = :index_id and a.archive=:archive_id")
			.addParameter("cache_id", cacheEntry.getId())
			.addParameter("index_id", index)
			.addParameter("archive_id", archive)
			.addColumnMapping("index", "indexId")
			.addColumnMapping("archive", "archiveId")
			.addColumnMapping("data_id", "dataId")
			.executeAndFetchFirst(ArchiveEntry.class);
	}

	byte[] getArchiveData(ArchiveEntry archiveEntry)
	{
		return conn.createQuery("select data from data where id = :data_id")
			.addParameter("data_id", archiveEntry.getDataId())
			.executeAndFetchFirst(byte[].class);
	}

	ArchiveEntry findArchiveByName(CacheEntry cache, int index, int name)
	{
		return conn.createQuery("select a.id, a.index, a.archive, a.crc, a.name, a.revision, a.data_id from cache_archive ca " +
			"join archive a on ca.archive_id = a.id where ca.cache_id = :cache_id and a.index = :index_id and a.name = :name")
			.addParameter("cache_id", cache.getId())
			.addParameter("index_id", index)
			.addParameter("name", name)
			.addColumnMapping("index", "indexId")
			.addColumnMapping("archive", "archiveId")
			.addColumnMapping("data_id", "dataId")
			.executeAndFetchFirst(ArchiveEntry.class);
	}
}
