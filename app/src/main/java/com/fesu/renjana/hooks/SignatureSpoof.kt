package com.fesu.renjana.hooks

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import com.fesu.renjana.utils.RenjanaLog
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

object SignatureSpoof {
    private const val TAG = "SignatureSpoof"

    private val signatureCache = ConcurrentHashMap<String, Array<Signature>>()
    private val signatureHashCache = ConcurrentHashMap<String, String>()
    private val signingInfoCache = ConcurrentHashMap<String, Any>()

    fun extractSignatures(
        apkPath: String,
        pm: PackageManager? = null,
        packageName: String? = null
    ): Array<Signature>? {
        if (pm != null && packageName != null) {
            try {
                val flags = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    @Suppress("DEPRECATION")
                    PackageManager.GET_SIGNATURES
                }
                val info = pm.getPackageInfo(packageName, flags)
                val sigs = extractFromPackageInfo(info)
                if (sigs != null && sigs.isNotEmpty()) {
                    RenjanaLog.d(TAG, "Extracted ${sigs.size} signature(s) via PackageManager for $packageName")
                    return sigs
                }
            } catch (e: PackageManager.NameNotFoundException) {
                RenjanaLog.d(TAG, "Package $packageName not installed, extracting from APK file")
            } catch (e: Exception) {
                RenjanaLog.w(TAG, "PackageManager extraction failed: ${e.message}")
            }
        }

        val v2v3Sigs = extractFromSigningBlock(apkPath)
        if (v2v3Sigs != null && v2v3Sigs.isNotEmpty()) {
            RenjanaLog.d(TAG, "Extracted ${v2v3Sigs.size} signature(s) from APK Signing Block")
            return v2v3Sigs
        }

        val v1Sigs = extractFromMetaInf(apkPath)
        if (v1Sigs != null && v1Sigs.isNotEmpty()) {
            RenjanaLog.d(TAG, "Extracted ${v1Sigs.size} signature(s) from META-INF (v1)")
            return v1Sigs
        }

        RenjanaLog.e(TAG, "Failed to extract any signatures from $apkPath")
        return null
    }

    @Suppress("DEPRECATION")
    private fun extractFromPackageInfo(info: PackageInfo): Array<Signature>? {
        return if (android.os.Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
    }

    private fun extractFromSigningBlock(apkPath: String): Array<Signature>? {
        try {
            val apkFile = File(apkPath)
            if (!apkFile.exists() || !apkFile.canRead()) {
                RenjanaLog.w(TAG, "APK file not accessible: $apkPath")
                return null
            }

            val raf = java.io.RandomAccessFile(apkFile, "r")
            try {
                val fileLen = raf.length()
                val eocdSearchStart = maxOf(0, fileLen - 65557)
                val searchBuf = ByteArray((fileLen - eocdSearchStart).toInt())
                raf.seek(eocdSearchStart)
                raf.readFully(searchBuf)

                var eocdOffset = -1L
                for (i in searchBuf.size - 22 downTo 0) {
                    if (searchBuf[i] == 0x50.toByte() &&
                        searchBuf[i + 1] == 0x4b.toByte() &&
                        searchBuf[i + 2] == 0x05.toByte() &&
                        searchBuf[i + 3] == 0x06.toByte()
                    ) {
                        eocdOffset = eocdSearchStart + i
                        break
                    }
                }

                if (eocdOffset < 0) {
                    RenjanaLog.w(TAG, "EOCD not found in $apkPath")
                    return null
                }

                raf.seek(eocdOffset + 16)
                val cdOffset = readLittleEndianInt32(raf)

                raf.seek(cdOffset.toLong() - 16)
                val blockSize2 = readLittleEndianInt64(raf)
                val magic = ByteArray(16)
                raf.readFully(magic)

                val magicStr = String(magic, Charsets.US_ASCII)
                if (!magicStr.startsWith("APK Sig Block 42")) {
                    RenjanaLog.d(TAG, "No APK Signing Block found (v1-only APK)")
                    return null
                }

                raf.seek(cdOffset.toLong() - blockSize2 - 8)
                val blockSize1 = readLittleEndianInt64(raf)

                val blockData = ByteArray(blockSize1.toInt())
                raf.readFully(blockData)

                val signatures = mutableListOf<Signature>()
                var pos = 0
                while (pos < blockData.size - 8) {
                    val pairLen = readLittleEndianInt64At(blockData, pos).toInt()
                    pos += 8
                    if (pairLen < 8 || pos + pairLen > blockData.size) break

                    val pairId = readLittleEndianInt32At(blockData, pos)
                    val pairValue = blockData.copyOfRange(pos + 4, pos + pairLen)
                    pos += pairLen

                    if (pairId == 0x7109871a.toInt() || pairId == 0xf05368c0.toInt()) {
                        val extracted = extractCertsFromSignerBlock(pairValue)
                        if (extracted != null) {
                            signatures.addAll(extracted)
                        }
                    }
                }

                return if (signatures.isNotEmpty()) signatures.toTypedArray() else null
            } finally {
                raf.close()
            }
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Signing block extraction failed: ${e.message}")
            return null
        }
    }

    private fun extractCertsFromSignerBlock(signerBlock: ByteArray): List<Signature>? {
        try {
            var pos = 0
            val signatures = mutableListOf<Signature>()

            if (pos + 4 > signerBlock.size) return null
            val seqLen = readLittleEndianInt32At(signerBlock, pos)
            pos += 4

            val seqEnd = pos + seqLen
            while (pos < seqEnd && pos < signerBlock.size - 4) {
                val signerLen = readLittleEndianInt32At(signerBlock, pos)
                pos += 4
                val signerEnd = pos + signerLen

                if (pos + 4 > signerEnd) break
                val signedDataLen = readLittleEndianInt32At(signerBlock, pos)
                pos += 4
                val signedDataEnd = pos + signedDataLen

                if (pos + 4 > signedDataEnd) {
                    pos = signerEnd
                    continue
                }
                val digestsLen = readLittleEndianInt32At(signerBlock, pos)
                pos += 4 + digestsLen

                if (pos + 4 > signedDataEnd) {
                    pos = signerEnd
                    continue
                }
                val certsLen = readLittleEndianInt32At(signerBlock, pos)
                pos += 4
                val certsEnd = pos + certsLen

                while (pos < certsEnd && pos + 4 <= signedDataEnd) {
                    val certLen = readLittleEndianInt32At(signerBlock, pos)
                    pos += 4
                    if (certLen > 0 && pos + certLen <= signedDataEnd) {
                        val certBytes = signerBlock.copyOfRange(pos, pos + certLen)
                        signatures.add(Signature(certBytes))
                    }
                    pos += certLen
                }

                pos = signerEnd
            }

            return if (signatures.isNotEmpty()) signatures else null
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Cert extraction from signer block failed: ${e.message}")
            return null
        }
    }

    private fun extractFromMetaInf(apkPath: String): Array<Signature>? {
        try {
            val zip = ZipFile(apkPath)
            val signatures = mutableListOf<Signature>()
            val entries = zip.entries()

            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name.uppercase()
                if (name.startsWith("META-INF/") &&
                    (name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC"))
                ) {
                    val certStream = zip.getInputStream(entry)
                    try {
                        val certFactory = CertificateFactory.getInstance("X.509")
                        val certs = certFactory.generateCertificates(certStream)
                        for (cert in certs) {
                            val x509 = cert as X509Certificate
                            signatures.add(Signature(x509.encoded))
                        }
                    } finally {
                        certStream.close()
                    }
                }
            }
            zip.close()

            return if (signatures.isNotEmpty()) signatures.toTypedArray() else null
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "META-INF extraction failed: ${e.message}")
            return null
        }
    }

    fun cacheSignatures(packageName: String, signatures: Array<Signature>) {
        signatureCache[packageName] = signatures
        RenjanaLog.d(TAG, "Cached ${signatures.size} signature(s) for $packageName")
    }

    fun getCachedSignatures(packageName: String): Array<Signature>? {
        return signatureCache[packageName]
    }

    fun storeOriginalSignatures(packageName: String, apkPath: String): Boolean {
        val cached = signatureCache[packageName]
        if (cached != null) {
            RenjanaLog.d(TAG, "Signatures already cached for $packageName, skipping")
            return true
        }

        val sigs = extractSignatures(apkPath)
        if (sigs != null && sigs.isNotEmpty()) {
            signatureCache[packageName] = sigs
            computeAndCacheHash(packageName, sigs)
            RenjanaLog.i(TAG, "Stored original signatures for $packageName (${sigs.size} certs)")
            return true
        }

        RenjanaLog.e(TAG, "Failed to store signatures for $packageName")
        return false
    }

    @Suppress("DEPRECATION")
    fun spoofPackageInfo(packageInfo: PackageInfo, packageName: String): Boolean {
        val originalSigs = signatureCache[packageName]
        if (originalSigs == null) {
            RenjanaLog.w(TAG, "No cached signatures for $packageName, cannot spoof")
            return false
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                val signingInfo = getOrCreateSigningInfo(packageName, originalSigs)
                if (signingInfo != null) {
                    val field = PackageInfo::class.java.getDeclaredField("signingInfo")
                    field.isAccessible = true
                    field.set(packageInfo, signingInfo)
                }
                packageInfo.signatures = originalSigs
            } else {
                packageInfo.signatures = originalSigs
            }

            RenjanaLog.d(TAG, "Spoofed signature for $packageName (${originalSigs.size} certs)")
            return true
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to spoof PackageInfo for $packageName: ${e.message}")
            return false
        }
    }

    private fun getOrCreateSigningInfo(packageName: String, signatures: Array<Signature>): Any? {
        signingInfoCache[packageName]?.let { return it }

        try {
            val signingInfoClass = Class.forName("android.content.pm.SigningInfo")

            try {
                val ctor = signingInfoClass.getDeclaredConstructor(Array<Signature>::class.java)
                ctor.isAccessible = true
                val info = ctor.newInstance(signatures)
                signingInfoCache[packageName] = info
                return info
            } catch (_: NoSuchMethodException) {
                // Fall through to Parcel approach
            }

            val parcelClass = Class.forName("android.os.Parcel")
            val obtainMethod = parcelClass.getMethod("obtain")
            val parcel = obtainMethod.invoke(null)

            try {
                val writeIntMethod = parcelClass.getMethod("writeInt", Int::class.javaPrimitiveType)
                val writeParcelableArrayMethod = parcelClass.getMethod(
                    "writeParcelableArray",
                    Array<android.os.Parcelable>::class.java,
                    Int::class.javaPrimitiveType
                )
                val setDataPositionMethod = parcelClass.getMethod("setDataPosition", Int::class.javaPrimitiveType)

                writeIntMethod.invoke(parcel, signatures.size)
                writeParcelableArrayMethod.invoke(parcel, signatures, 0)

                setDataPositionMethod.invoke(parcel, 0)
                val creatorField = signingInfoClass.getField("CREATOR")
                val creator = creatorField.get(null)
                val createFromParcelMethod = creator.javaClass.getMethod(
                    "createFromParcel",
                    parcelClass
                )
                val info = createFromParcelMethod.invoke(creator, parcel)
                signingInfoCache[packageName] = info!!
                return info
            } finally {
                val recycleMethod = parcelClass.getMethod("recycle")
                recycleMethod.invoke(parcel)
            }
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Failed to create SigningInfo: ${e.message}")
            return null
        }
    }

    private fun computeAndCacheHash(packageName: String, signatures: Array<Signature>) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(signatures[0].toByteArray())
            val hexString = hash.joinToString("") { "%02x".format(it) }
            signatureHashCache[packageName] = hexString
            RenjanaLog.d(TAG, "SHA-256 for $packageName: $hexString")
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Failed to compute signature hash: ${e.message}")
        }
    }

    fun getSignatureHash(packageName: String): String? {
        return signatureHashCache[packageName]
    }

    fun hasCachedSignatures(packageName: String): Boolean {
        return signatureCache.containsKey(packageName)
    }

    fun clearCache(packageName: String) {
        signatureCache.remove(packageName)
        signatureHashCache.remove(packageName)
        signingInfoCache.remove(packageName)
        RenjanaLog.d(TAG, "Cleared signature cache for $packageName")
    }

    fun clearAllCaches() {
        signatureCache.clear()
        signatureHashCache.clear()
        signingInfoCache.clear()
        RenjanaLog.i(TAG, "All signature caches cleared")
    }

    private fun readLittleEndianInt32(raf: java.io.RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return readLittleEndianInt32At(b, 0)
    }

    private fun readLittleEndianInt32At(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readLittleEndianInt64(raf: java.io.RandomAccessFile): Long {
        val lo = readLittleEndianInt32(raf).toLong() and 0xFFFFFFFFL
        val hi = readLittleEndianInt32(raf).toLong() and 0xFFFFFFFFL
        return (hi shl 32) or lo
    }

    private fun readLittleEndianInt64At(data: ByteArray, offset: Int): Long {
        val lo = readLittleEndianInt32At(data, offset).toLong() and 0xFFFFFFFFL
        val hi = readLittleEndianInt32At(data, offset + 4).toLong() and 0xFFFFFFFFL
        return (hi shl 32) or lo
    }
}
