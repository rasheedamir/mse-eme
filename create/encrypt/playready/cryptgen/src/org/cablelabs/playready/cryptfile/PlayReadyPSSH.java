/* Copyright (c) 2014, CableLabs, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package org.cablelabs.playready.cryptfile;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import org.apache.commons.codec.binary.Base64;
import org.cablelabs.cryptfile.Bitstream;
import org.cablelabs.cryptfile.DRMInfoPSSH;
import org.cablelabs.playready.WRMHeader;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Generates PlayReady-specific PSSH for MP4Box cryptfiles
 */
public class PlayReadyPSSH extends DRMInfoPSSH {
    
    private static final String MSPRO_ELEMENT = "mspr:pro";
    
    private static final byte[] PLAYREADY_SYSTEM_ID = {
        (byte)0x9a, (byte)0x04, (byte)0xf0, (byte)0x79,
        (byte)0x98, (byte)0x40, (byte)0x42, (byte)0x86,
        (byte)0xab, (byte)0x92, (byte)0xe6, (byte)0x5b,
        (byte)0xe0, (byte)0x88, (byte)0x5f, (byte)0x95
    };
    
    private List<WRMHeader> wrmHeaders;
    private int wrmHeadersSize = 0;
    private int proSize;
    private ContentProtectionType cpType;
    
    /**
     * License server URL to use for testing
     */
    public static final String TEST_URL = "http://playready.directtaps.net/pr/svc/rightsmanager.asmx?PlayRight=1&UseSimpleNonPersistentLicense=1";
    
    /**
     * Two different types of ContentProtection elements can be generated base on
     * the PSSH data.  One is a custom Microsoft format (MSPRO) and the other
     * is a standard CommonEncryption format (CENC)
     */
    public enum ContentProtectionType {
        MSPRO,
        CENC
    };

    /**
     * Returns whether or not the given systemID is PlayReady
     * 
     * @param systemID the systemID to check
     * @return true if the systemID is PlayReady, false otherwise
     */
    public static boolean isPlayReady(byte[] systemID) {
        return systemIDMatch(PLAYREADY_SYSTEM_ID, systemID);
    }
    
    public PlayReadyPSSH(List<WRMHeader> wrmHeaders, ContentProtectionType cpType) {
        super(PLAYREADY_SYSTEM_ID);
        this.wrmHeaders = wrmHeaders;
        this.cpType = cpType;
        
        // Collect all of our WRMHeader data arrays so that we can calculate the
        // total size
        for (WRMHeader header : wrmHeaders) {
            wrmHeadersSize += header.getWRMHeaderData().length;
        }
        
        // Total size of PlayReady Header Object is:
        // 
        //    4          PlayReady Header Object Size field
        //    2          Number of Records field
        //    4*NumRec   Record Type and Record Length field for each record
        //    RecSize    Size of all headers
        proSize = 4 + 2 + (4*wrmHeaders.size()) + wrmHeadersSize;
    }

    @Override
    public Element generateContentProtection(Document d) throws IOException {
        Element e = super.generateContentProtection(d);
        
        switch (cpType) {
        
            case CENC:
                e.appendChild(generateCENCContentProtectionData(d));
                break;
                
            case MSPRO:
                Element pro = d.createElement(MSPRO_ELEMENT);
                
                // Generate base64-encoded PRO
                ByteBuffer ba = ByteBuffer.allocate(4);
                ba.order(ByteOrder.LITTLE_ENDIAN);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);
                
                // PlayReady Header Object Size field
                ba.putInt(0, proSize);
                dos.write(ba.array());
                
                // Number of Records field
                ba.putShort(0, (short)wrmHeaders.size());
                dos.write(ba.array(), 0, 2);
                
                for (WRMHeader header : wrmHeaders) {
                    
                    byte[] wrmData = header.getWRMHeaderData();
                    
                    // Record Type (always 1 for WRM Headers)
                    ba.putShort(0, (short)1);
                    dos.write(ba.array(), 0, 2);
                    
                    // Record Length
                    ba.putShort(0, (short)wrmData.length);
                    dos.write(ba.array(), 0, 2);
                    
                    // Data
                    dos.write(wrmData);
                }
                
                pro.setTextContent(Base64.encodeBase64String(baos.toByteArray()));
                
                e.appendChild(pro);
                break;
        }
        
        return e;
    }
    
    // Helper to convert ints of varying widths (in bytes), to a little endian byte array
    private byte[] toLEBytes(int input, int width) {
        byte[] retVal = new byte[width];
        for (int i = 0; i < width; i++) {
            retVal[i] = (byte)((input >> (i*8)) & 0xFF);
        }
        return retVal;
    }
    

    @Override
    protected void generatePSSHData(DataOutputStream dos) throws IOException {
        
        // PlayReady Header Object Size field
        dos.write(toLEBytes(proSize, 4));
        
        // Number of Records field
        dos.write(toLEBytes(wrmHeaders.size(), 2));
        
        for (WRMHeader header : wrmHeaders) {
            
            byte[] wrmData = header.getWRMHeaderData();
            
            // Record Type (always 1 for WRM Headers)
            dos.write(toLEBytes(1, 2));
            
            // Record Length
            dos.write(toLEBytes(wrmData.length, 2));
            
            // Data
            dos.write(wrmData);
        }
    }

    /*
     * (non-Javadoc)
     * @see org.cablelabs.cryptfile.MP4BoxXML#generateXML(org.w3c.dom.Document)
     */
    @Override
    public Node generateXML(Document d) {
        
        Element e = generateDRMInfo(d);
        Bitstream b = new Bitstream();
        
        // PlayReady Header Object Size field
        b.setupIntegerLE(proSize, 32);
        e.appendChild(b.generateXML(d));
        
        // Number of Records field
        b.setupIntegerLE(wrmHeaders.size(), 16);
        e.appendChild(b.generateXML(d));
        
        for (WRMHeader header : wrmHeaders) {
            
            byte[] wrmData = header.getWRMHeaderData();
            
            // Record Type (always 1 for WRM Headers)
            b.setupIntegerLE(1, 16);
            e.appendChild(b.generateXML(d));
            
            // Record Length
            b.setupIntegerLE(wrmData.length, 16);
            e.appendChild(b.generateXML(d));
            
            // Data
            b.setupDataB64(wrmData);
            e.appendChild(b.generateXML(d));
        }
        
        return e;
    }
}
