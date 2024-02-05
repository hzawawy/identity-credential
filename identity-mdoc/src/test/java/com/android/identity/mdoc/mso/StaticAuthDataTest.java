/*
 * Copyright 2022 The Android Open Source Project
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

package com.android.identity.mdoc.mso;

import com.android.identity.cbor.Bstr;
import com.android.identity.cbor.Cbor;
import com.android.identity.cbor.CborArray;
import com.android.identity.cbor.CborMap;
import com.android.identity.cbor.DataItem;
import com.android.identity.cbor.DiagnosticOption;
import com.android.identity.cbor.Simple;
import com.android.identity.cbor.Tagged;
import com.android.identity.internal.Util;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("deprecation")
public class StaticAuthDataTest {

    private Map<String, List<byte[]>> createValidDigestIdMapping() {
        DataItem issuerSignedItemMetadata = CborMap.Companion.builder()
                .put("random", new byte[] {0x50, 0x51, 0x52})
                .put("digestID", 42)
                .put("elementIdentifier", "dataElementName")
                .put("elementValue", Simple.Companion.getNULL())
                .end()
                .build();
        DataItem isiMetadataBytes =
                new Tagged(24, new Bstr(Cbor.encode(issuerSignedItemMetadata)));
        byte[] encodedIsiMetadataBytes = Cbor.encode(isiMetadataBytes);

        DataItem issuerSignedItemMetadata2 = CborMap.Companion.builder()
                .put("digestID", 43)
                .put("random", new byte[] {0x53, 0x54, 0x55})
                .put("elementIdentifier", "dataElementName2")
                .put("elementValue", Simple.Companion.getNULL())
                .end()
                .build();
        DataItem isiMetadata2Bytes =
                new Tagged(24, new Bstr(Cbor.encode(issuerSignedItemMetadata2)));
        byte[] encodedIsiMetadata2Bytes = Cbor.encode(isiMetadata2Bytes);

        DataItem issuerSignedItemMetadata3 = CborMap.Companion.builder()
                .put("digestID", 44)
                .put("random", new byte[] {0x53, 0x54, 0x55})
                .put("elementIdentifier", "portrait")
                .put("elementValue", Cbor.encode(new Bstr(new byte[] {0x20, 0x21, 0x22, 0x23})))
                .end()
                .build();
        DataItem isiMetadata3Bytes =
                new Tagged(24, new Bstr(Cbor.encode(issuerSignedItemMetadata3)));
        byte[] encodedIsiMetadata3Bytes = Cbor.encode(isiMetadata3Bytes);

        Map<String, List<byte[]>> issuerSignedMapping = new HashMap<>();
        issuerSignedMapping.put("org.namespace",
                Arrays.asList(encodedIsiMetadataBytes,
                        encodedIsiMetadata2Bytes,
                        encodedIsiMetadata3Bytes));
        return issuerSignedMapping;
    }

    private byte[] createValidIssuerAuth() {
        byte[] encodedIssuerAuth = Cbor.encode(CborArray.Companion.builder()
                .addArray()
                .end()
                .add(Simple.Companion.getNULL())
                .add(new byte[] {0x01, 0x02})
                .end()
                .build());
        return encodedIssuerAuth;
    }

    @Test
    public void testStaticAuthData() {
        // This test checks that the order of the maps in IssuerSignedItem is preserved
        // when using Utility.encodeStaticAuthData() and that no canonicalization takes
        // place.

        Map<String, List<byte[]>> issuerSignedMapping = createValidDigestIdMapping();

        byte[] encodedIssuerAuth = createValidIssuerAuth();

        byte[] staticAuthData =
                new StaticAuthDataGenerator(issuerSignedMapping, encodedIssuerAuth).generate();

        Assert.assertEquals(
                "{\n" +
                        "  \"digestIdMapping\": {\n" +
                        "    \"org.namespace\": [\n" +
                        "      24(<< {\n" +
                        "        \"random\": h'505152',\n" +
                        "        \"digestID\": 42,\n" +
                        "        \"elementIdentifier\": \"dataElementName\",\n" +
                        "        \"elementValue\": null\n" +
                        "      } >>),\n" +
                        "      24(<< {\n" +
                        "        \"digestID\": 43,\n" +
                        "        \"random\": h'535455',\n" +
                        "        \"elementIdentifier\": \"dataElementName2\",\n" +
                        "        \"elementValue\": null\n" +
                        "      } >>),\n" +
                        "      24(<< {\n" +
                        "        \"digestID\": 44,\n" +
                        "        \"random\": h'535455',\n" +
                        "        \"elementIdentifier\": \"portrait\",\n" +
                        "        \"elementValue\": h'4420212223'\n" +
                        "      } >>)\n" +
                        "    ]\n" +
                        "  },\n" +
                        "  \"issuerAuth\": [\n" +
                        "    [],\n" +
                        "    null,\n" +
                        "    h'0102'\n" +
                        "  ]\n" +
                        "}",
                Cbor.toDiagnostics(
                        staticAuthData,
                        Set.of(DiagnosticOption.EMBEDDED_CBOR, DiagnosticOption.PRETTY_PRINT)));

        // Now check we can decode it
        StaticAuthDataParser.StaticAuthData decodedStaticAuthData = new StaticAuthDataParser(staticAuthData).parse();
        Map<String, List<byte[]>> digestIdMapping = decodedStaticAuthData.getDigestIdMapping();

        // Check that the IssuerSignedItem instances are correctly decoded and the order
        // of the map matches what was put in above
        Assert.assertEquals(1, digestIdMapping.size());
        List<byte[]> list = digestIdMapping.get("org.namespace");
        Assert.assertNotNull(list);
        Assert.assertEquals(3, list.size());
        Assert.assertEquals(
                "24(<< {\n" +
                        "  \"random\": h'505152',\n" +
                        "  \"digestID\": 42,\n" +
                        "  \"elementIdentifier\": \"dataElementName\",\n" +
                        "  \"elementValue\": null\n" +
                        "} >>)",
                Cbor.toDiagnostics(
                        list.get(0),
                        Set.of(DiagnosticOption.EMBEDDED_CBOR, DiagnosticOption.PRETTY_PRINT)));
        Assert.assertEquals(
                "24(<< {\n" +
                        "  \"digestID\": 43,\n" +
                        "  \"random\": h'535455',\n" +
                        "  \"elementIdentifier\": \"dataElementName2\",\n" +
                        "  \"elementValue\": null\n" +
                        "} >>)",
                Cbor.toDiagnostics(
                        list.get(1),
                        Set.of(DiagnosticOption.EMBEDDED_CBOR, DiagnosticOption.PRETTY_PRINT)));

        byte[] issuerAuth = decodedStaticAuthData.getIssuerAuth();
        Assert.assertArrayEquals(encodedIssuerAuth, issuerAuth);
    }

    @Test
    public void testStaticAuthDataExceptions() {
        Assert.assertThrows("expect exception for empty digestIDMapping",
                IllegalArgumentException.class,
                () -> new StaticAuthDataGenerator(new HashMap<>(), createValidIssuerAuth())
                        .generate());
    }
}
