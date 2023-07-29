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
package net.runelite.http.service.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.ItemDefinition;
import net.runelite.cache.definitions.loaders.ItemLoader;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.Container;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.index.ArchiveData;
import net.runelite.cache.index.FileData;
import net.runelite.cache.index.IndexData;
import net.runelite.http.service.cache.beans.ArchiveEntry;
import net.runelite.http.service.cache.beans.CacheEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.sql2o.Connection;
import org.sql2o.Sql2o;

@Service
@Slf4j
public class CacheService
{
	@Autowired
	@Qualifier("Runelite Cache SQL2O")
	private Sql2o sql2o;

	/**
	 * retrieve archive from storage
	 *
	 * @param archiveEntry
	 * @return
	 */
	public byte[] getArchive(ArchiveEntry archiveEntry)
	{
		try (Connection con = sql2o.open())
		{
			CacheDAO cacheDao = new CacheDAO(con);
			return cacheDao.getArchiveData(archiveEntry);
		}
	}

	public CacheEntry findMostRecent()
	{
		try (Connection con = sql2o.open())
		{
			CacheDAO cacheDao = new CacheDAO(con);
			return cacheDao.findMostRecent();
		}
	}

	public ArchiveEntry findArchiveForTypeAndName(CacheEntry cache, int index, int nameHash)
	{
		try (Connection con = sql2o.open())
		{
			CacheDAO cacheDao = new CacheDAO(con);
			return cacheDao.findArchiveByName(cache, index, nameHash);
		}
	}

	private ArchiveFiles loadArchiveFiles(int indexId, int archiveId) throws IOException
	{
		try (Connection con = sql2o.open())
		{
			CacheDAO cacheDao = new CacheDAO(con);
			CacheEntry cache = cacheDao.findMostRecent();
			if (cache == null)
			{
				return null;
			}

			// file data required to parse the archive is in the index header, so read that first
			ArchiveFiles archiveFiles;
			{
				ArchiveEntry idx = cacheDao.findArchive(cache, 255, indexId);
				byte[] data = cacheDao.getArchiveData(idx);
				Container container = Container.decompress(data, null);
				IndexData indexData = new IndexData();
				indexData.load(container.data);
				ArchiveData itemArchive = Arrays.stream(indexData.getArchives())
					.filter(a -> a.getId() == archiveId)
					.findFirst()
					.get();
				FileData[] files = itemArchive.getFiles();

				archiveFiles = new ArchiveFiles();
				for (FileData fileData : files)
				{
					FSFile file = new FSFile(fileData.getId());
					file.setNameHash(fileData.getNameHash());
					archiveFiles.addFile(file);
				}
			}

			// now read the archive and unpack the files
			ArchiveEntry archive = cacheDao.findArchive(cache, indexId, archiveId);
			byte[] data = cacheDao.getArchiveData(archive);
			Container container = Container.decompress(data, null);
			archiveFiles.loadContents(container.data);
			return archiveFiles;
		}
	}

	public List<ItemDefinition> getItems() throws IOException
	{
		ArchiveFiles files = loadArchiveFiles(IndexType.CONFIGS.getNumber(), ConfigType.ITEM.getId());
		if (files == null)
		{
			return Collections.emptyList();
		}

		final ItemLoader itemLoader = new ItemLoader();
		final List<ItemDefinition> result = new ArrayList<>(files.getFiles().size());
		for (FSFile file : files.getFiles())
		{
			ItemDefinition itemDef = itemLoader.load(file.getFileId(), file.getContents());
			result.add(itemDef);
		}
		return result;
	}
}
