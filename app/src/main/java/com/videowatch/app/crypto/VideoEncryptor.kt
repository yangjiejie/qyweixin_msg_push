package com.videowatch.app.crypto

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 视频加解密工具 - AES-256-GCM + PBKDF2
 * 密钥: 900822
 */
object VideoEncryptor {

    private const val KEY_STRING = "900822"
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_DERIVATION = "PBKDF2WithHmacSHA256"
    private const val KEY_SIZE = 256
    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH = 12
    private const val SALT_LENGTH = 16
    private const val ITERATIONS = 10000
    private const val ENCRYPTED_EXTENSION = ".enc"

    fun isEncrypted(file: File): Boolean {
        return file.name.endsWith(ENCRYPTED_EXTENSION)
    }

    fun getDecryptedFileName(file: File): String {
        val name = file.name
        return if (name.endsWith(ENCRYPTED_EXTENSION)) {
            name.substring(0, name.length - ENCRYPTED_EXTENSION.length)
        } else name
    }

    /**
     * 加密文件，返回加密后的文件路径，失败返回null
     */
    fun encrypt(inputPath: String): String? {
        return try {
            val inputFile = File(inputPath)
            val outputFile = File(inputPath + ENCRYPTED_EXTENSION)
            encryptFile(inputFile, outputFile)
            outputFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 解密文件，返回解密后的文件路径，失败返回null
     */
    fun decrypt(inputPath: String): String? {
        return try {
            val inputFile = File(inputPath)
            val name = inputFile.name
            val outName = if (name.endsWith(ENCRYPTED_EXTENSION)) {
                name.substring(0, name.length - ENCRYPTED_EXTENSION.length)
            } else {
                name + ".dec"
            }
            val outputFile = File(inputFile.parent, outName)
            decryptFile(inputFile, outputFile)
            outputFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 解密到指定路径
     */
    fun decryptToFile(inputPath: String, outputPath: String): Boolean {
        return try {
            decryptFile(File(inputPath), File(outputPath))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 将加密文件移动到App私有目录（防止相册扫描到）
     * 返回新路径，失败返回null
     */
    fun moveToPrivateDir(encFilePath: String, privateDir: File): String? {
        return try {
            val encFile = File(encFilePath)
            val targetFile = File(privateDir, encFile.name)
            // 如果目标已存在则覆盖
            if (targetFile.exists()) targetFile.delete()
            val success = encFile.renameTo(targetFile)
            if (success) targetFile.absolutePath else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ==================== 内部实现 ====================

    @Throws(Exception::class)
    private fun encryptFile(inputFile: File, outputFile: File) {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        val iv = ByteArray(IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val key = deriveKey(KEY_STRING.toCharArray(), salt)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        // 文件格式: [salt(16)][iv(12)][密文]
        FileOutputStream(outputFile).use { fos ->
            fos.write(salt)
            fos.write(iv)
            CipherOutputStream(fos, cipher).use { cos ->
                FileInputStream(inputFile).use { fis ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        cos.write(buffer, 0, bytesRead)
                    }
                }
            }
        }
    }

    @Throws(Exception::class)
    private fun decryptFile(inputFile: File, outputFile: File) {
        FileInputStream(inputFile).use { fis ->
            val salt = ByteArray(SALT_LENGTH)
            fis.read(salt)
            val iv = ByteArray(IV_LENGTH)
            fis.read(iv)

            val key = deriveKey(KEY_STRING.toCharArray(), salt)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

            FileOutputStream(outputFile).use { fos ->
                CipherInputStream(fis, cipher).use { cis ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (cis.read(buffer).also { bytesRead = it } != -1) {
                        fos.write(buffer, 0, bytesRead)
                    }
                }
            }
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance(KEY_DERIVATION)
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_SIZE)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }
}
