/*
 * Copyright 2010, 2011, 2012 mapsforge.org
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.mapsforge.map.controller.layer.map;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.mapsforge.core.model.Tile;
import org.mapsforge.core.util.IOUtils;
import org.mapsforge.core.util.LRUCache;

import android.graphics.Bitmap;

/**
 * A thread-safe cache for image files with a fixed size and LRU policy.
 */
public class FileSystemTileCache implements TileCache {
	private static class FileLRUCache extends LRUCache<Tile, File> {
		private static final long serialVersionUID = 1L;

		FileLRUCache(int capacity) {
			super(capacity);
		}

		@Override
		protected boolean removeEldestEntry(java.util.Map.Entry<Tile, File> eldest) {
			if (size() > getCapacity()) {
				remove(eldest.getKey());
				if (!eldest.getValue().delete()) {
					eldest.getValue().deleteOnExit();
				}
			}

			return false;
		}
	}

	private static final class ImageFileNameFilter implements FilenameFilter {
		static final FilenameFilter INSTANCE = new ImageFileNameFilter();

		private ImageFileNameFilter() {
			// do nothing
		}

		@Override
		public boolean accept(File directory, String fileName) {
			return fileName.endsWith(IMAGE_FILE_NAME_EXTENSION);
		}
	}

	private static final String IMAGE_FILE_NAME_EXTENSION = ".tile";
	private static final Logger LOGGER = Logger.getLogger(FileSystemTileCache.class.getName());

	private static File checkDirectory(File file) {
		if (!file.exists() && !file.mkdirs()) {
			throw new IllegalArgumentException("could not create directory: " + file);
		} else if (!file.isDirectory()) {
			throw new IllegalArgumentException("not a directory: " + file);
		} else if (!file.canRead()) {
			throw new IllegalArgumentException("cannot read directory: " + file);
		} else if (!file.canWrite()) {
			throw new IllegalArgumentException("cannot write directory: " + file);
		}
		return file;
	}

	private final ByteBuffer byteBuffer;
	private final File cacheDirectory;
	private long cacheId;
	private final LRUCache<Tile, File> lruCache;

	/**
	 * @param capacity
	 *            the maximum number of entries in this cache.
	 * @param cacheDirectory
	 *            the directory where cached tiles will be stored.
	 * @throws IllegalArgumentException
	 *             if the capacity is negative.
	 */
	public FileSystemTileCache(int capacity, File cacheDirectory) {
		this.lruCache = new FileLRUCache(capacity);
		this.cacheDirectory = checkDirectory(cacheDirectory);

		this.byteBuffer = ByteBuffer.allocate(Tile.TILE_SIZE * Tile.TILE_SIZE * 4);
	}

	@Override
	public synchronized boolean containsKey(Tile tile) {
		return this.lruCache.containsKey(tile);
	}

	@Override
	public synchronized void destroy() {
		this.lruCache.clear();

		File[] filesToDelete = this.cacheDirectory.listFiles(ImageFileNameFilter.INSTANCE);
		if (filesToDelete != null) {
			for (File file : filesToDelete) {
				if (!file.delete()) {
					file.deleteOnExit();
				}
			}
		}
	}

	@Override
	public synchronized Bitmap get(Tile tile, Bitmap bitmap) {
		File file = this.lruCache.get(tile);
		if (file == null) {
			return null;
		}

		InputStream inputStream = null;
		try {
			byte[] bytesArray = this.byteBuffer.array();
			inputStream = new FileInputStream(file);
			int bytesRead = inputStream.read(bytesArray);
			if (bytesRead != file.length()) {
				this.lruCache.remove(tile);
				LOGGER.log(Level.SEVERE, "could not read file: " + file);
				return null;
			}

			this.byteBuffer.rewind();
			bitmap.copyPixelsFromBuffer(this.byteBuffer);
			return bitmap;
		} catch (IOException e) {
			this.lruCache.remove(tile);
			LOGGER.log(Level.SEVERE, null, e);
			return null;
		} finally {
			IOUtils.closeQuietly(inputStream);
		}
	}

	@Override
	public synchronized int getCapacity() {
		return this.lruCache.getCapacity();
	}

	@Override
	public synchronized void put(Tile tile, Bitmap bitmap) {
		if (this.lruCache.getCapacity() == 0) {
			return;
		}

		OutputStream outputStream = null;
		try {
			File file = getOutputFile();

			this.byteBuffer.rewind();
			bitmap.copyPixelsToBuffer(this.byteBuffer);

			outputStream = new FileOutputStream(file);
			outputStream.write(this.byteBuffer.array(), 0, this.byteBuffer.position());

			this.lruCache.put(tile, file);
		} catch (IOException e) {
			LOGGER.log(Level.SEVERE, null, e);
		} finally {
			IOUtils.closeQuietly(outputStream);
		}
	}

	private File getOutputFile() {
		while (true) {
			++this.cacheId;
			File file = new File(this.cacheDirectory, this.cacheId + IMAGE_FILE_NAME_EXTENSION);
			if (!file.exists()) {
				return file;
			}
		}
	}
}
