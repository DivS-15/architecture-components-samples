/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.background.workers.filters

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.background.Constants
import com.example.background.library.R
import com.example.background.workers.createNotification
import java.io.*
import java.util.*

abstract class BaseFilterWorker(
    context: Context,
    parameters: WorkerParameters
) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val resourceUri = inputData.getString(Constants.KEY_IMAGE_URI)
            ?: throw IllegalArgumentException("Invalid input uri")
        return try {
            val inputStream = inputStreamFor(applicationContext, resourceUri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            val output = applyFilter(bitmap)
            // write bitmap to a file and set the output
            val outputUri = writeBitmapToFile(output)
            Result.success(workDataOf(Constants.KEY_IMAGE_URI to outputUri.toString()))
        } catch (fileNotFoundException: FileNotFoundException) {
            Log.e(TAG, "Failed to decode input stream", fileNotFoundException)
            Result.failure()
        } catch (throwable: Throwable) {
            Log.e(TAG, "Error applying filter", throwable)
            Result.failure()
        }
    }

    abstract fun applyFilter(input: Bitmap): Bitmap

    /**
     * Writes a given [Bitmap] to the [Context.getFilesDir] directory.
     *
     * @param bitmap the [Bitmap] which needs to be written to the files directory.
     * @return a [Uri] to the output [Bitmap].
     */
    private fun writeBitmapToFile(
        bitmap: Bitmap
    ): Uri {

        // Bitmaps are being written to a temporary directory. This is so they can serve as inputs
        // for workers downstream, via Worker chaining.
        val name = "filter-output-${UUID.randomUUID()}.png"
        val outputDir = File(applicationContext.filesDir, Constants.OUTPUT_PATH)
        if (!outputDir.exists()) {
            outputDir.mkdirs() // should succeed
        }
        val outputFile = File(outputDir, name)
        var out: FileOutputStream? = null
        try {
            out = FileOutputStream(outputFile)
            bitmap.compress(Bitmap.CompressFormat.PNG, 0 /* ignored for PNG */, out)
        } finally {
            if (out != null) {
                try {
                    out.close()
                } catch (ignore: IOException) {
                }
            }
        }
        return Uri.fromFile(outputFile)
    }

    /**
     * Create ForegroundInfo required to run a Worker in a foreground service.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            NOTIFICATION_ID, createNotification(
                applicationContext, id,
                applicationContext.getString(R.string.notification_title_filtering_image)
            )
        )
    }

    companion object {
        const val TAG = "BaseFilterWorker"
        const val ASSET_PREFIX = "file:///android_asset/"

        // For a real world app you might want to use a different id for each Notification.
        const val NOTIFICATION_ID = 1

        /**
         * Creates an input stream which can be used to read the given `resourceUri`.
         *
         * @param context the application [Context].
         * @param resourceUri the [String] resourceUri.
         * @return the [InputStream] for the resourceUri.
         */
        @VisibleForTesting
        fun inputStreamFor(
            context: Context,
            resourceUri: String
        ): InputStream? {

            // If the resourceUri is an Android asset URI, then use AssetManager to get a handle to
            // the input stream. (Stock Images are Asset URIs).
            return if (resourceUri.startsWith(ASSET_PREFIX)) {
                val assetManager = context.resources.assets
                assetManager.open(resourceUri.substring(ASSET_PREFIX.length))
            } else {
                // Not an Android asset Uri. Use a ContentResolver to get a handle to the input stream.
                val resolver = context.contentResolver
                resolver.openInputStream(Uri.parse(resourceUri))
            }
        }
    }
}
