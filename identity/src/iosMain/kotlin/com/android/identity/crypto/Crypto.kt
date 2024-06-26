package com.android.identity.crypto

import kotlinx.datetime.Instant

import com.android.identity.swiftcrypto.SwiftCrypto
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
actual object Crypto {

    /**
     * CryptoKit supports the following curves from [EcCurve].
     *
     * TODO: CryptoKit actually supports ED25519 and X25519, add support for this too.
     */
    actual val supportedCurves: Set<EcCurve> = setOf(
        EcCurve.P256,
        EcCurve.P384,
        EcCurve.P521,
    )

    actual fun digest(
        algorithm: Algorithm,
        message: ByteArray
    ): ByteArray {
        return when (algorithm) {
            Algorithm.SHA256 -> SwiftCrypto.sha256(message.toNSData()).toByteArray()
            Algorithm.SHA384 -> SwiftCrypto.sha384(message.toNSData()).toByteArray()
            Algorithm.SHA512 -> SwiftCrypto.sha512(message.toNSData()).toByteArray()
            else -> throw IllegalArgumentException("Unsupported algorithm $algorithm")
        }
    }

    actual fun mac(
        algorithm: Algorithm,
        key: ByteArray,
        message: ByteArray
    ): ByteArray {
        return when (algorithm) {
            Algorithm.HMAC_SHA256 -> SwiftCrypto.hmacSha256(key.toNSData(), message.toNSData()).toByteArray()
            Algorithm.HMAC_SHA384 -> SwiftCrypto.hmacSha384(key.toNSData(), message.toNSData()).toByteArray()
            Algorithm.HMAC_SHA512 -> SwiftCrypto.hmacSha512(key.toNSData(), message.toNSData()).toByteArray()
            else -> throw IllegalArgumentException("Unsupported algorithm $algorithm")
        }
    }

    actual fun encrypt(
        algorithm: Algorithm,
        key: ByteArray,
        nonce: ByteArray,
        messagePlaintext: ByteArray
    ): ByteArray {
        return SwiftCrypto.aesGcmEncrypt(
            key.toNSData(),
            messagePlaintext.toNSData(),
            nonce.toNSData()
        ).toByteArray()
    }

    actual fun decrypt(
        algorithm: Algorithm,
        key: ByteArray,
        nonce: ByteArray,
        messageCiphertext: ByteArray
    ): ByteArray {
        return SwiftCrypto.aesGcmDecrypt(
            key.toNSData(),
            messageCiphertext.toNSData(),
            nonce.toNSData()
        )?.toByteArray() ?: throw IllegalStateException("Decryption failed")
    }

    actual fun hkdf(
        algorithm: Algorithm,
        ikm: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        size: Int
    ): ByteArray {
        val hashLen = when (algorithm) {
            Algorithm.HMAC_SHA256 -> 32
            Algorithm.HMAC_SHA384 -> 48
            Algorithm.HMAC_SHA512 -> 64
            else -> throw IllegalArgumentException("Unsupported algorithm $algorithm")
        }
        return SwiftCrypto.hkdf(
            hashLen.toLong(),
            ikm.toNSData(),
            (if (salt != null && salt.size > 0) salt else ByteArray(hashLen)).toNSData(),
            info!!.toNSData(),
            size.toLong()
        )?.toByteArray() ?: throw IllegalStateException("HKDF not available")
    }

    actual fun checkSignature(
        publicKey: EcPublicKey,
        message: ByteArray,
        algorithm: Algorithm,
        signature: EcSignature
    ): Boolean {
        val raw = when (publicKey) {
            is EcPublicKeyDoubleCoordinate -> publicKey.x + publicKey.y
            is EcPublicKeyOkp -> publicKey.x
        }
        return SwiftCrypto.ecVerifySignature(
            publicKey.curve.coseCurveIdentifier.toLong(),
            raw.toNSData(),
            message.toNSData(),
            (signature.r + signature.s).toNSData()
        )
    }

    actual fun createEcPrivateKey(curve: EcCurve): EcPrivateKey {
        val ret = SwiftCrypto.createEcPrivateKey(curve.coseCurveIdentifier.toLong()) as List<NSData>
        if (ret.size == 0) {
            throw UnsupportedOperationException("Curve is not supported")
        }
        val privKeyBytes = ret[0].toByteArray()
        val pubKeyBytes = ret[1].toByteArray()
        val x = pubKeyBytes.sliceArray(IntRange(0, pubKeyBytes.size/2 - 1))
        val y = pubKeyBytes.sliceArray(IntRange(pubKeyBytes.size/2, pubKeyBytes.size - 1))
        return EcPrivateKeyDoubleCoordinate(curve, privKeyBytes, x, y)
    }

    actual fun sign(
        key: EcPrivateKey,
        signatureAlgorithm: Algorithm,
        message: ByteArray
    ): EcSignature {
        val rawSignature = SwiftCrypto.ecSign(
            key.curve.coseCurveIdentifier.toLong(),
            key.d.toNSData(),
            message.toNSData()
        )?.toByteArray() ?: throw UnsupportedOperationException("Curve is not supported")

        val r = rawSignature.sliceArray(IntRange(0, rawSignature.size/2 - 1))
        val s = rawSignature.sliceArray(IntRange(rawSignature.size/2, rawSignature.size - 1))
        return EcSignature(r, s)
    }

    actual fun keyAgreement(
        key: EcPrivateKey,
        otherKey: EcPublicKey
    ): ByteArray {
        val otherKeyRaw = when (otherKey) {
            is EcPublicKeyDoubleCoordinate -> otherKey.x + otherKey.y
            is EcPublicKeyOkp -> otherKey.x
        }
        return SwiftCrypto.ecKeyAgreement(
            key.curve.coseCurveIdentifier.toLong(),
            key.d.toNSData(),
            otherKeyRaw.toNSData()
        )?.toByteArray() ?: throw UnsupportedOperationException("Curve is not supported")
    }

    actual fun hpkeEncrypt(
        cipherSuite: Algorithm,
        receiverPublicKey: EcPublicKey,
        plainText: ByteArray,
        aad: ByteArray
    ): Pair<ByteArray, EcPublicKey> {
        require(cipherSuite == Algorithm.HPKE_BASE_P256_SHA256_AES128GCM)
        val receiverPublicKeyRaw = when (receiverPublicKey) {
            is EcPublicKeyDoubleCoordinate -> receiverPublicKey.x + receiverPublicKey.y
            is EcPublicKeyOkp -> receiverPublicKey.x
        }
        val ret = SwiftCrypto.hpkeEncrypt(
            receiverPublicKeyRaw.toNSData(),
            plainText.toNSData(),
            aad.toNSData()
        ) as List<NSData>
        if (ret.size == 0) {
            throw IllegalStateException("HPKE not supported on this iOS version")
        }
        val encapsulatedPublicKeyRaw = ret[0].toByteArray()
        val encapsulatedPublicKey = EcPublicKeyDoubleCoordinate.fromUncompressedPointEncoding(
            EcCurve.P256,
            encapsulatedPublicKeyRaw
        )
        val cipherText = ret[1].toByteArray()
        return Pair(cipherText, encapsulatedPublicKey)
    }

    actual fun hpkeDecrypt(
        cipherSuite: Algorithm,
        receiverPrivateKey: EcPrivateKey,
        cipherText: ByteArray,
        aad: ByteArray,
        encapsulatedPublicKey: EcPublicKey
    ): ByteArray {
        require(cipherSuite == Algorithm.HPKE_BASE_P256_SHA256_AES128GCM)
        val receiverPrivateKeyRaw = receiverPrivateKey.d
        val ret = SwiftCrypto.hpkeDecrypt(
            receiverPrivateKeyRaw.toNSData(),
            cipherText.toNSData(),
            aad.toNSData(),
            (encapsulatedPublicKey as EcPublicKeyDoubleCoordinate).asUncompressedPointEncoding.toNSData()
        )
        if (ret == null) {
            throw IllegalStateException("HPKE not supported on this iOS version")
        }
        return ret.toByteArray()
    }

    internal actual fun ecPublicKeyToPem(publicKey: EcPublicKey): String {
        val raw = when (publicKey) {
            is EcPublicKeyDoubleCoordinate -> publicKey.x + publicKey.y
            is EcPublicKeyOkp -> publicKey.x
        }
        val pemEncoding = SwiftCrypto.ecPublicKeyToPem(
            publicKey.curve.coseCurveIdentifier.toLong(),
            raw.toNSData()
        ) ?: throw IllegalStateException("Not available")
        if (pemEncoding == "") {
            throw UnsupportedOperationException("Curve is not supported")
        }
        return pemEncoding
    }

    internal actual fun ecPublicKeyFromPem(
        pemEncoding: String,
        curve: EcCurve
    ): EcPublicKey {
        val rawEncoding = SwiftCrypto.ecPublicKeyFromPem(
            curve.coseCurveIdentifier.toLong(),
            pemEncoding
        )?.toByteArray() ?: throw IllegalStateException("Not available")
        val x = rawEncoding.sliceArray(IntRange(0, rawEncoding.size/2 - 1))
        val y = rawEncoding.sliceArray(IntRange(rawEncoding.size/2, rawEncoding.size - 1))
        return EcPublicKeyDoubleCoordinate(curve, x, y)
    }

    internal actual fun ecPrivateKeyToPem(privateKey: EcPrivateKey): String {
        val pemEncoding = SwiftCrypto.ecPrivateKeyToPem(
            privateKey.curve.coseCurveIdentifier.toLong(),
            privateKey.d.toNSData()
        ) ?: throw IllegalStateException("Not available")
        if (pemEncoding == "") {
            throw UnsupportedOperationException("Curve is not supported")
        }
        return pemEncoding
    }

    internal actual fun ecPrivateKeyFromPem(
        pemEncoding: String,
        publicKey: EcPublicKey
    ): EcPrivateKey {
        val rawEncoding = SwiftCrypto.ecPrivateKeyFromPem(
            publicKey.curve.coseCurveIdentifier.toLong(),
            pemEncoding
        )?.toByteArray() ?: throw IllegalStateException("Not available")
        publicKey as EcPublicKeyDoubleCoordinate
        return EcPrivateKeyDoubleCoordinate(publicKey.curve, rawEncoding, publicKey.x, publicKey.y)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    return ByteArray(length.toInt()).apply {
        usePinned {
            memcpy(it.addressOf(0), bytes, length)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = memScoped {
    NSData.create(bytes = allocArrayOf(this@toNSData), length = this@toNSData.size.toULong())
}