/*
 * Copyright 2023 The Android Open Source Project
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
package com.android.identity.credential

import com.android.identity.cbor.Bstr
import com.android.identity.cbor.Cbor
import com.android.identity.cbor.CborMap
import com.android.identity.cbor.DataItem
import com.android.identity.cbor.Tagged
import com.android.identity.cbor.Tstr
import com.android.identity.cbor.dataItem

/**
 * Key/value pairs, organized by name space.
 *
 * This class implements a data model which consists of a series of name spaces
 * (identified by a string such as *org.iso.18013.5.1*) where each name space
 * contains a number of key/value pairs where keys are strings and values are
 * [CBOR](https://datatracker.ietf.org/doc/html/rfc8949) values.
 *
 * While this happens to be similar to the mDL/MDOC data model used in
 * [ISO/IEC 18013-5:2021](https://www.iso.org/standard/69084.html),
 * it's flexible enough to be used to store credential data for any kind of
 * credential.
 *
 * This type is immutable.
 */
class NameSpacedData private constructor(
    private val map: MutableMap<String, MutableMap<String, ByteArray>>
) {
    /**
     * Names of all the namespaces.
     */
    val nameSpaceNames: List<String>
        get() = map.keys.toList()

    /**
     * Gets all data elements in a given namespace.
     *
     * @param nameSpaceName the name space name.
     * @return list of all data element names in the name space.
     * @throws IllegalArgumentException if the given namespace doesn't exist.
     */
    fun getDataElementNames(nameSpaceName: String) =
        map[nameSpaceName]?.keys?.toList()
            ?: throw IllegalArgumentException("No such namespace '$nameSpaceName'")
    /**
     * Checks if there's a value for a given data element.
     *
     * @param nameSpaceName the name space name.
     * @param dataElementName the data element name.
     * @return `true` if there's a value for the data element, `false` otherwise.
     */
    fun hasDataElement(
        nameSpaceName: String,
        dataElementName: String
    ) = map[nameSpaceName]?.run { return this[dataElementName] != null } ?: false

    /**
     * Gets the raw CBOR for a data element.
     *
     * @param nameSpaceName the name space name.
     * @param dataElementName the data element name.
     * @return the bytes of the CBOR.
     * @throws IllegalArgumentException if the given name space doesn't exist.
     * @throws IllegalArgumentException if the given data element doesn't exist.
     */
    fun getDataElement(
        nameSpaceName: String,
        dataElementName: String
    ): ByteArray {
        val innerMap = map[nameSpaceName]
            ?: throw IllegalArgumentException("No such namespace '$nameSpaceName'")
        return innerMap[dataElementName]
            ?: throw IllegalArgumentException("No such data element '$dataElementName'")
    }

    /**
     * Like [.getDataElement] but decodes the CBOR as a string.
     *
     * @param nameSpaceName the name space name.
     * @param dataElementName the data element name.
     * @return the decoded data.
     * @throws IllegalArgumentException if the given name space doesn't exist.
     * @throws IllegalArgumentException if the given data eoement doesn't exist.
     * @throws IllegalArgumentException if the given data element isn't a string.
     */
    fun getDataElementString(
        nameSpaceName: String,
        dataElementName: String
    ) = Cbor.decode(getDataElement(nameSpaceName, dataElementName)).asTstr

    /**
     * Like [.getDataElement] but decodes the CBOR as a byte string.
     *
     * @param nameSpaceName the name space name.
     * @param dataElementName the data element name.
     * @return the decoded data.
     * @throws IllegalArgumentException if the given name space doesn't exist.
     * @throws IllegalArgumentException if the given data eoement doesn't exist.
     * @throws IllegalArgumentException if the given data element isn't a byte string.
     */
    fun getDataElementByteString(
        nameSpaceName: String,
        dataElementName: String
    ) = Cbor.decode(getDataElement(nameSpaceName, dataElementName)).asBstr

    /**
     * Like [.getDataElement] but decodes the CBOR as a number.
     *
     * @param nameSpaceName the name space name.
     * @param dataElementName the data element name.
     * @return the decoded data.
     * @throws IllegalArgumentException if the given name space doesn't exist.
     * @throws IllegalArgumentException if the given data eoement doesn't exist.
     * @throws IllegalArgumentException if the given data element isn't a number.
     */
    fun getDataElementNumber(
        nameSpaceName: String,
        dataElementName: String
    ) = Cbor.decode(getDataElement(nameSpaceName, dataElementName)).asNumber

    /**
     * Like [.getDataElement] but decodes the CBOR as a boolean.
     *
     * @param nameSpaceName the name space name.
     * @param dataElementName the data element name.
     * @return the decoded data.
     * @throws IllegalArgumentException if the given name space doesn't exist.
     * @throws IllegalArgumentException if the given data eoement doesn't exist.
     * @throws IllegalArgumentException if the given data element isn't a boolean.
     */
    fun getDataElementBoolean(
        nameSpaceName: String,
        dataElementName: String
    ) = Cbor.decode(getDataElement(nameSpaceName, dataElementName)).asBoolean

    fun toCbor(): DataItem {
        val mapBuilder = CborMap.builder()
        for (namespaceName in map.keys) {
            val innerMapBuilder = mapBuilder.putMap(namespaceName)
            val namespace = map[namespaceName]!!
            for ((dataElementName, dataElementValue) in namespace) {
                innerMapBuilder.putTagged(
                    dataElementName,
                    Tagged.ENCODED_CBOR,
                    Bstr(dataElementValue)
                )
            }
        }
        return mapBuilder.end().build()
    }

    /**
     * Encodes the given [NameSpacedData] as CBOR.
     *
     * The encoding uses the following CDDL:
     * ```
     * NameSpacedCbor = {
     * + NameSpaceName =&gt; DataElements
     * }
     *
     * NameSpaceName = tstr
     *
     * DataElements = [
     * + DataElementName =&gt; DataElementValueBytes
     * ]
     *
     * DataElementName = tstr
     * DataElementValue = any
     * DataElementValueBytes = #6.24(bstr .cbor DataElementValue)
     * ```
     *
     * Here's an example of CBOR conforming to the above CDDL printed in diagnostic form:
     * ```
     * {
     *   "org.iso.18013.5.1" : {
     *     "given_name" : 24(&lt;&lt; "Erika" &gt;&gt;),
     *     "family_name" : 24(&lt;&lt; "Mustermann" &gt;&gt;),
     *   },
     *   "org.iso.18013.5.1.aamva" : {
     *     "organ_donor" : 24(&lt;&lt; 1 &gt;&gt;)
     *   }
     * }
     * ```
     *
     * Name spaces and data elements will be in the order they were inserted using
     * at construction time, either using the [.Builder] or [.fromEncodedCbor].
     *
     * @return the bytes of the encoding describe above.
     */
    fun encodeAsCbor() = Cbor.encode(toCbor())

    /**
     * A builder for [NameSpacedData].
     */
    class Builder {
        private val map = mutableMapOf<String, MutableMap<String, ByteArray>>()

        /**
         * Adds a raw CBOR value to the builder.
         *
         * For performance-reasons the passed in value isn't validated and it's the
         * responsibility of the application to perform this check if for example operating
         * on untrusted data.
         *
         * @param nameSpaceName the name space name.
         * @param dataElementName the data element name.
         * @param value the bytes of the CBOR.
         * @return the builder.
         */
        fun putEntry(
            nameSpaceName: String,
            dataElementName: String,
            value: ByteArray
        ): Builder {
            map.putIfAbsent(nameSpaceName, mutableMapOf())
            map[nameSpaceName]?.run {
                this[dataElementName] = value
            }
            return this
        }

        /**
         * Encode the given value as `tstr` CBOR and adds it to the builder.
         *
         * @param nameSpaceName the name space name.
         * @param dataElementName the data element name.
         * @param value A string.
         * @return the builder.
         */
        fun putEntryString(
            nameSpaceName: String,
            dataElementName: String,
            value: String
        ) = putEntry(nameSpaceName, dataElementName, Cbor.encode(value.dataItem))

        /**
         * Encode the given value as `bstr` CBOR and adds it to the builder.
         *
         * @param nameSpaceName the name space name.
         * @param dataElementName the data element name.
         * @param value A byte string.
         * @return the builder.
         */
        fun putEntryByteString(
            nameSpaceName: String,
            dataElementName: String,
            value: ByteArray
        ) = putEntry(nameSpaceName, dataElementName, Cbor.encode(value.dataItem))

        /**
         * Encode the given value as an integer or unsigned integer and adds it to the builder.
         *
         * @param nameSpaceName the name space name.
         * @param dataElementName the data element name.
         * @param value The value, as a `long`.
         * @return the builder.
         */
        fun putEntryNumber(
            nameSpaceName: String,
            dataElementName: String,
            value: Long
        ) = putEntry(nameSpaceName, dataElementName, Cbor.encode(value.dataItem))

        /**
         * Encode the given value as a boolean and adds it to the builder.
         *
         * @param nameSpaceName the name space name.
         * @param dataElementName the data element name.
         * @param value The value, as a `boolean`..
         * @return the builder.
         */
        fun putEntryBoolean(
            nameSpaceName: String,
            dataElementName: String,
            value: Boolean
        ) = putEntry(nameSpaceName, dataElementName, Cbor.encode(value.dataItem))

        /**
         * Builds a [NameSpacedData] from the builder
         *
         * @return a [NameSpacedData] instance.
         */
        fun build(): NameSpacedData = NameSpacedData(map)
    }

    companion object {
        /**
         * Creates a new [NameSpacedData] from encoded CBOR.
         *
         * @param encodedCbor CBOR encoded in the format described by [.encodeAsCbor].
         * @return A [NameSpacedData].
         * @throws IllegalArgumentException if the given data is not valid CBOR.
         * @throws IllegalArgumentException if the given data does not confirm to the CDDL in
         * [.encodeAsCbor].
         */
        @JvmStatic
        fun fromEncodedCbor(encodedCbor: ByteArray) = fromCbor(Cbor.decode(encodedCbor))

        private fun fromCbor(dataItem: DataItem): NameSpacedData {
            val ret = mutableMapOf<String, MutableMap<String, ByteArray>>()
            val map = dataItem
            require(map is CborMap)
            for (nameSpaceNameItem in map.items.keys) {
                require(nameSpaceNameItem is Tstr)
                val namespaceName = nameSpaceNameItem.asTstr
                val dataElementToValueMap = mutableMapOf<String, ByteArray>()
                val dataElementItems = map[namespaceName]
                require(dataElementItems is CborMap)
                for (dataElementNameItem in dataElementItems.items.keys) {
                    require(dataElementNameItem is Tstr)
                    val dataElementName = dataElementNameItem.asTstr
                    val taggedValueItem = dataElementItems[dataElementNameItem]
                    require(taggedValueItem is Tagged && taggedValueItem.tagNumber == Tagged.ENCODED_CBOR)
                    val valueItem = taggedValueItem.taggedItem
                    require(valueItem is Bstr)
                    dataElementToValueMap[dataElementName] = valueItem.value
                }
                ret[namespaceName] = dataElementToValueMap
            }
            return NameSpacedData(ret)
        }
    }
}