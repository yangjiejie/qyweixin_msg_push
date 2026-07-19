package com.videowatch.app.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.LinkedList

/**
 * GIF 动画制作工具
 * 支持从视频帧序列生成 GIF 文件
 */
object GifMaker {

    private const val GIF_DELAY = 10 // 100ms per frame (10fps)
    private const val MAX_COLORS = 256

    /**
     * 将多帧 Bitmap 合成为 GIF 文件
     * @param frames 帧列表
     * @param outputFile 输出的 GIF 文件
     * @param fps 帧率
     */
    fun createGif(
        frames: List<Bitmap>,
        outputFile: File,
        fps: Int = 10
    ): Boolean {
        if (frames.isEmpty()) return false

        return try {
            val delay = (100f / fps).toInt().coerceAtLeast(1)
            val encoder = GifEncoder(outputFile)
            encoder.start()

            for (frame in frames) {
                encoder.addFrame(frame, delay)
            }

            encoder.finish()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * GIF 编码器
     * 实现 GIF89a 格式的编码
     */
    private class GifEncoder(private val outputFile: File) {

        private var outputStream: OutputStream? = null
        private var width = 0
        private var height = 0
        private var colorTable: IntArray? = null
        private var transparentIndex = -1

        fun start() {
            outputStream = FileOutputStream(outputFile)
        }

        fun addFrame(bitmap: Bitmap, delay: Int) {
            if (outputStream == null) return

            val scaledBitmap = if (bitmap.width > 480) {
                val ratio = 480f / bitmap.width
                Bitmap.createScaledBitmap(bitmap, 480, (bitmap.height * ratio).toInt(), true)
            } else bitmap

            width = scaledBitmap.width
            height = scaledBitmap.height

            val pixels = IntArray(width * height)
            scaledBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            // 第一次写入时添加头部和全局信息
            if (outputStream is FileOutputStream) {
                writeHeader()
                writeLogicalScreenDescriptor()
            }

            // 生成局部调色板
            val palette = generatePalette(pixels, MAX_COLORS)
            val colorSize = getColorTableSize(palette.size)

            // 写入图形控制扩展（含延迟）
            writeGraphicControlExtension(delay, transparentIndex)

            // 写入图像描述符
            writeImageDescriptor(0, 0, width, height, colorSize)

            // 写入局部颜色表
            writeColorTable(palette)

            // LZW 压缩并写入图像数据
            val indexedPixels = mapPixelsToPalette(pixels, palette)
            writeLZWData(indexedPixels, colorSize + 1)

            if (bitmap !== scaledBitmap) {
                scaledBitmap.recycle()
            }
        }

        fun finish() {
            // 写入文件结束符
            outputStream?.write(0x3B)
            outputStream?.flush()
            outputStream?.close()
            outputStream = null
        }

        private fun writeHeader() {
            outputStream?.apply {
                write("GIF89a".toByteArray())
            }
        }

        private fun writeLogicalScreenDescriptor() {
            outputStream?.apply {
                writeShort(width.toShort())
                writeShort(height.toShort())
                // Packed: 全局颜色表标志=0, 颜色分辨率=7, 排序=0, 全局颜色表大小=0
                write(0x70)
                write(0) // 背景色索引
                write(0) // 像素宽高比
            }
        }

        private fun writeGraphicControlExtension(delay: Int, transparent: Int) {
            outputStream?.apply {
                write(0x21) // Extension Introducer
                write(0xF9) // Graphic Control Label
                write(4)    // Block Size
                var packed = 0
                if (transparent >= 0) packed = packed or 0x08 // Transparent Color Flag
                write(packed)
                writeShort(delay.toShort())
                write(if (transparent >= 0) transparent else 0)
                write(0) // Block Terminator
            }
        }

        private fun writeImageDescriptor(
            x: Int, y: Int, w: Int, h: Int,
            colorSize: Int
        ) {
            outputStream?.apply {
                write(0x2C) // Image Separator
                writeShort(x.toShort())
                writeShort(y.toShort())
                writeShort(w.toShort())
                writeShort(h.toShort())
                // Packed: 局部颜色表=1, 交错=0, 排序=0, 保留=0, 颜色表大小
                val packed = 0x80 or (colorSize and 0x07)
                write(packed)
            }
        }

        private fun writeColorTable(colors: IntArray) {
            outputStream?.apply {
                val paddedColors = colors + IntArray(MAX_COLORS - colors.size) { 0 }
                for (color in paddedColors) {
                    write((color shr 16) and 0xFF) // R
                    write((color shr 8) and 0xFF)  // G
                    write(color and 0xFF)          // B
                }
            }
        }

        private fun writeLZWData(pixels: IntArray, minCodeSize: Int) {
            outputStream?.apply {
                write(minCodeSize)

                val clearCode = 1 shl (minCodeSize - 1)
                val eoiCode = clearCode + 1
                var codeSize = minCodeSize
                var maxCode = (1 shl codeSize) - 1

                val dict = LinkedHashMap<String, Int>()
                // 初始化字典：单像素值
                for (i in 0 until clearCode) {
                    dict[i.toString()] = i
                }
                dict["CLR"] = clearCode
                dict["END"] = eoiCode
                var nextCode = eoiCode + 1

                val bitBuffer = ByteArrayOutputStream()
                val bitWriter = BitPacker(bitBuffer)

                // 输出清除码
                bitWriter.writeBits(clearCode, codeSize)

                if (pixels.isEmpty()) return

                var w = pixels[0].toString()
                for (i in 1 until pixels.size) {
                    val k = pixels[i].toString()
                    val wk = "$w,$k"

                    if (dict.containsKey(wk)) {
                        w = wk
                    } else {
                        bitWriter.writeBits(dict[w] ?: 0, codeSize)
                        if (nextCode < 4096) {
                            dict[wk] = nextCode++
                        }

                        if (nextCode > maxCode && codeSize < 12) {
                            codeSize++
                            maxCode = (1 shl codeSize) - 1
                        }

                        // 字典满了，发送清除码并重置
                        if (nextCode >= 4096) {
                            bitWriter.writeBits(clearCode, codeSize)
                            dict.clear()
                            for (j in 0 until clearCode) {
                                dict[j.toString()] = j
                            }
                            dict["CLR"] = clearCode
                            dict["END"] = eoiCode
                            nextCode = eoiCode + 1
                            codeSize = minCodeSize
                            maxCode = (1 shl codeSize) - 1
                        }
                        w = k
                    }
                }
                bitWriter.writeBits(dict[w] ?: 0, codeSize)
                bitWriter.writeBits(eoiCode, codeSize)
                bitWriter.flush()

                // 按子块写出
                val data = bitBuffer.toByteArray()
                var offset = 0
                while (offset < data.size) {
                    val blockSize = minOf(255, data.size - offset)
                    write(blockSize)
                    write(data, offset, blockSize)
                    offset += blockSize
                }
                write(0) // Block Terminator
            }
        }

        private fun generatePalette(pixels: IntArray, maxColors: Int): IntArray {
            // 简单的中值切割量化
            val colorSet = HashSet<Int>()
            for (pixel in pixels) {
                if (pixel and 0xFF000000.toInt() != 0) {
                    colorSet.add(pixel and 0x00FFFFFF)
                }
                if (colorSet.size >= maxColors) break
            }

            val colors = colorSet.toIntArray()
            return if (colors.size <= maxColors) {
                colors
            } else {
                // 降采样到 maxColors
                quantizeColors(pixels, maxColors)
            }
        }

        private fun quantizeColors(pixels: IntArray, maxColors: Int): IntArray {
            // 简化的颜色量化：按RGB量化到较少的位
            val bitsPerChannel = when {
                maxColors >= 256 -> 6 // 64 * 64 * 64 = 262144 可能，实际最多256
                maxColors >= 64 -> 3  // 8 * 8 * 4 = 256
                maxColors >= 8 -> 2   // 4 * 4 * 4 = 64
                else -> 1             // 2 * 2 * 2 = 8
            }

            val shift = 8 - bitsPerChannel
            val colorMap = LinkedHashMap<Int, Int>()

            for (pixel in pixels) {
                val rgb = pixel and 0x00FFFFFF
                if (rgb == 0) continue
                val r = ((rgb shr 16) and 0xFF) shr shift
                val g = ((rgb shr 8) and 0xFF) shr shift
                val b = (rgb and 0xFF) shr shift
                val key = (r shl (bitsPerChannel * 2)) or (g shl bitsPerChannel) or b
                if (!colorMap.containsKey(key)) {
                    val nr = ((r shl shift) or ((1 shl (shift - 1)) - 1)).coerceAtMost(255)
                    val ng = ((g shl shift) or ((1 shl (shift - 1)) - 1)).coerceAtMost(255)
                    val nb = ((b shl shift) or ((1 shl (shift - 1)) - 1)).coerceAtMost(255)
                    colorMap[key] = (nr shl 16) or (ng shl 8) or nb
                }
                if (colorMap.size >= maxColors) break
            }

            return colorMap.values.toIntArray()
        }

        private fun mapPixelsToPalette(pixels: IntArray, palette: IntArray): IntArray {
            val result = IntArray(pixels.size)
            for (i in pixels.indices) {
                val rgb = pixels[i] and 0x00FFFFFF
                var bestIndex = 0
                var bestDist = Int.MAX_VALUE
                for (j in palette.indices) {
                    val pr = (palette[j] shr 16) and 0xFF
                    val pg = (palette[j] shr 8) and 0xFF
                    val pb = palette[j] and 0xFF
                    val r = (rgb shr 16) and 0xFF
                    val g = (rgb shr 8) and 0xFF
                    val b = rgb and 0xFF
                    val dist = (r - pr) * (r - pr) + (g - pg) * (g - pg) + (b - pb) * (b - pb)
                    if (dist < bestDist) {
                        bestDist = dist
                        bestIndex = j
                    }
                }
                result[i] = bestIndex
            }
            return result
        }

        private fun getColorTableSize(colorCount: Int): Int {
            var size = 0
            var count = 2
            while (count < colorCount && size < 7) {
                size++
                count = count shl 1
            }
            return size
        }

        private fun OutputStream.writeShort(value: Short) {
            write(value.toInt() and 0xFF)
            write((value.toInt() shr 8) and 0xFF)
        }

        /**
         * 位打包写入器
         */
        private class BitPacker(private val output: OutputStream) {
            private var currentByte = 0
            private var bitCount = 0

            fun writeBits(value: Int, numBits: Int) {
                var remaining = numBits
                var v = value
                while (remaining > 0) {
                    val bitsToWrite = minOf(remaining, 8 - bitCount)
                    currentByte = currentByte or ((v and ((1 shl bitsToWrite) - 1)) shl bitCount)
                    bitCount += bitsToWrite
                    v = v shr bitsToWrite
                    remaining -= bitsToWrite
                    if (bitCount == 8) {
                        output.write(currentByte)
                        currentByte = 0
                        bitCount = 0
                    }
                }
            }

            fun flush() {
                if (bitCount > 0) {
                    output.write(currentByte)
                    currentByte = 0
                    bitCount = 0
                }
            }
        }
    }
}
