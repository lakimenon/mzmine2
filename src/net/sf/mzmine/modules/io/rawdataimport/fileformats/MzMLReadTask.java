/*
 * Copyright 2006-2008 The MZmine Development Team
 * 
 * This file is part of MZmine.
 * 
 * MZmine is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 * 
 * MZmine is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * MZmine; if not, write to the Free Software Foundation, Inc., 51 Franklin St,
 * Fifth Floor, Boston, MA 02110-1301 USA
 */

package net.sf.mzmine.modules.io.rawdataimport.fileformats;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.logging.Logger;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import net.sf.mzmine.data.DataPoint;
import net.sf.mzmine.data.PreloadLevel;
import net.sf.mzmine.data.RawDataFile;
import net.sf.mzmine.data.RawDataFileWriter;
import net.sf.mzmine.data.impl.SimpleDataPoint;
import net.sf.mzmine.data.impl.SimpleScan;
import net.sf.mzmine.main.MZmineCore;
import net.sf.mzmine.taskcontrol.Task;

import org.jfree.xml.util.Base64;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * This class read 1.04 and 1.05 MZDATA files.
 */
public class MzMLReadTask extends DefaultHandler implements Task {

	private Logger logger = Logger.getLogger(this.getClass().getName());

	private File originalFile;
	private RawDataFileWriter newMZmineFile;
	private PreloadLevel preloadLevel;
	private TaskStatus status;
	private int totalScans = -1;
	private int parsedScans;
	private int peaksCount = 0;
	private String errorMessage;
	private StringBuilder charBuffer;
	private Boolean precursorFlag = false;
	private Boolean spectrumDescriptionFlag = false;
	private Boolean scanFlag = false;
	private Boolean ionSelectionFlag = false;
	private Boolean binaryDataArrayFlag = false;
	private Boolean mzArrayBinaryFlag = false;
	private Boolean intenArrayBinaryFlag = false;
	private Boolean centroided = false;
	//private Boolean compressFlag = false;
	//private int compressedLen;
	private String precision;
	private int scanNumber;
	private int msLevel;
	private int parentScan;
	private float retentionTime;
	private float precursorMz;
	private int precursorCharge = 0;	
	
	private HashMap<String,Integer> scanId = new HashMap<String,Integer>();
	
	/*
	 * The information of "m/z" & "int" is content in two arrays because the mzData standard
	 * manages this information in two different tags. 
	 */
	private double[] mzDataPoints = new double[0];
	private double[] intensityDataPoints = new double[0];

	/*
	 * This variable hold the current scan or fragment, it is send to the stack
	 * when another scan/fragment appears as a parser.startElement
	 */
	private SimpleScan buildingScan;

	/*
	 * This stack stores at most 10 consecutive scans. This window serves to find possible
	 * fragments (current scan) that belongs to any of the stored scans in the stack. The
	 * reason of the size follows the concept of neighborhood of scans and all his fragments.
	 * These solution is implemented because exists the possibility to find fragments of one
	 * 
	 * TODO Verify this part of comment.
	 * 
	 * scan after one or more full scans. The file myo_full_1.05cv.mzML/myo_full_1.04cv.mzML,
	 * provided by Proteomics Standards Initiative as example, shows this condition in the 
	 * order of scans and fragments.
	 * 
	 * http://sourceforge.net/projects/psidev/ 
	 */
	private LinkedList<SimpleScan> parentStack;

	public MzMLReadTask(File fileToOpen, PreloadLevel preloadLevel) {
		originalFile = fileToOpen;
		status = TaskStatus.WAITING;
		this.preloadLevel = preloadLevel;
		charBuffer = new StringBuilder(2048);
		parentStack = new LinkedList<SimpleScan>();
		
	}

	/**
	 * @see net.sf.mzmine.taskcontrol.Task#getTaskDescription()
	 */
	public String getTaskDescription() {
		return "Opening file " + originalFile;
	}

	/**
	 * @see net.sf.mzmine.taskcontrol.Task#getFinishedPercentage()
	 */
	public float getFinishedPercentage() {
		return totalScans == 0 ? 0 : (float) parsedScans / totalScans;
	}

	/**
	 * @see net.sf.mzmine.taskcontrol.Task#getStatus()
	 */
	public TaskStatus getStatus() {
		return status;
	}

	/**
	 * @see net.sf.mzmine.taskcontrol.Task#getErrorMessage()
	 */
	public String getErrorMessage() {
		return errorMessage;
	}

	/**
	 * @see java.lang.Runnable#run()
	 */
	public void run() {

		status = TaskStatus.PROCESSING;
		logger.info("Started parsing file " + originalFile);

		// Use the default (non-validating) parser
		SAXParserFactory factory = SAXParserFactory.newInstance();

		try {

			newMZmineFile = MZmineCore.createNewFile(originalFile.getName(),
					preloadLevel);

			SAXParser saxParser = factory.newSAXParser();
			saxParser.parse(originalFile, this);

			// Close file
			RawDataFile finalRawDataFile = newMZmineFile.finishWriting();
			MZmineCore.getCurrentProject().addFile(finalRawDataFile);

		} catch (Throwable e) {
			/* we may already have set the status to CANCELED */
			e.printStackTrace();
			if (status == TaskStatus.PROCESSING)
				status = TaskStatus.ERROR;
			errorMessage = e.toString();
			return;
		}

		if (parsedScans == 0) {
			status = TaskStatus.ERROR;
			errorMessage = "No scans found";
			return;
		}

		logger.info("Finished parsing " + originalFile + ", parsed "
				+ parsedScans + " scans");
		status = TaskStatus.FINISHED;

	}

	/**
	 * @see net.sf.mzmine.taskcontrol.Task#cancel()
	 */
	public void cancel() {
		logger.info("Cancelling opening of MZXML file " + originalFile);
		status = TaskStatus.CANCELED;
	}

	public void startElement(String namespaceURI, String lName, // local name
			String qName, // qualified name
			Attributes attrs) throws SAXException {

		if (status == TaskStatus.CANCELED)
			throw new SAXException("Parsing Cancelled");

		// <spectrumList>
		if (qName.equals("spectrumList")) {
			String s = attrs.getValue("count");
			if (s != null)
				totalScans = Integer.parseInt(s);
		}

		// <spectrum>
		if (qName.equalsIgnoreCase("spectrum")) {
			retentionTime = 0f;
			parentScan = -1;
			precursorMz = 0f;
			precision = null;
			precursorCharge = 0;
			msLevel = Integer.parseInt(attrs.getValue("msLevel"));
			scanNumber = Integer.parseInt(attrs.getValue("scanNumber"));
			scanId.put(attrs.getValue("id"), (Integer) scanNumber);
		}

		// <spectrumDescription> 
		if (qName.equalsIgnoreCase("spectrumDescription")) {
			spectrumDescriptionFlag = true;
		}

		// <scan> 
		if (qName.equalsIgnoreCase("scan")) {
			scanFlag = true;
		}
		
		// <ionSelection> 
		if (qName.equalsIgnoreCase("ionSelection")) {
			ionSelectionFlag = true;
		}
		// <precursor>
		if (qName.equalsIgnoreCase("precursor")) {
			String parent = attrs.getValue("spectrumRef");
			if (parent != null){
				if (scanId.containsKey(parent))
					parentScan = (int) scanId.get(parent);
				else
					parentScan = -1;
			}
			else
				parentScan = -1;
			precursorFlag = true;
		}
		
		// <cvParam>
		if (qName.equalsIgnoreCase("cvParam")) {
			String accession = attrs.getValue("accession");
			if (spectrumDescriptionFlag) {
				if (accession.equals("MS:1000127")) {
					centroided = true;
				}
			}
			if (scanFlag) {
				if (accession.equals("MS:1000016")) {
					String unitAccession = attrs.getValue("unitAccession"); 
					if (unitAccession != null){
						if (unitAccession.equals("MS:1000038")) 
						retentionTime = Float.parseFloat(attrs.getValue("value")) * 60f;
					}
					else 
						retentionTime = Float.parseFloat(attrs.getValue("value"));
				}
			}
			
			if ((precursorFlag) && (ionSelectionFlag)) {
				if (accession.equals("MS:1000040")) 
					precursorMz = Float.parseFloat(attrs.getValue("value"));
				if (accession.equals("MS:1000041")) 
					precursorCharge = Integer.parseInt(attrs.getValue("value"));
			}
			if (binaryDataArrayFlag) {
				if (accession.equals("MS:1000514")) 
					mzArrayBinaryFlag = true;
				if (accession.equals("MS:1000515")) 
					intenArrayBinaryFlag = true;
				if (accession.equals("MS:1000521")) 
					precision = "32";
				if (accession.equals("MS:1000523")) 
					precision = "64";
				/*
				 * At present the mzML standard does not define the compressed length
				 * attribute of the binary data.
				 */
				//if (attrs.getValue("accession").equals("MS:1000574")) 
					//compressFlag = true;
					//compressedLen = Integer.parseInt(attrs.getValue("value"));
			}
		}

		// <binaryDataArray>
		if (qName.equalsIgnoreCase("binaryDataArray")) {
			// clean the current char buffer for the new element
			peaksCount = Integer.parseInt(attrs.getValue("arrayLength"));
			binaryDataArrayFlag = true;
		}

	}

	/**
	 * endElement()
	 */
	public void endElement(String namespaceURI, String sName, // simple name
			String qName // qualified name
	) throws SAXException {

		// <spectrumDescription> 
		if (qName.equalsIgnoreCase("spectrumDescription")) {
			spectrumDescriptionFlag = false;
		}

		// <scan> 
		if (qName.equalsIgnoreCase("scan")) {
			scanFlag = false;
		}
		
		// <precursor>
		if (qName.equalsIgnoreCase("precursor")) {
			precursorFlag = false;
		}

		// <ionSelection> 
		if (qName.equalsIgnoreCase("ionSelection")) {
			ionSelectionFlag = false;
		}
		
		// <spectrum>
		if (qName.equalsIgnoreCase("spectrum")) {

			if (mzDataPoints.length != intensityDataPoints.length) {
				status = TaskStatus.ERROR;
				errorMessage = "Corrupt list of peaks of scan number " + scanNumber;
				throw new SAXException("Parsing Cancelled");
			}
				
			DataPoint completeDataPoints[] = new DataPoint[peaksCount];
			DataPoint tempDataPoints[] = new DataPoint[peaksCount];

			// Copy m/z and intensity data
			for (int i = 0; i < completeDataPoints.length; i++) {
				completeDataPoints[i] = new SimpleDataPoint(
						(float) mzDataPoints[i], (float) intensityDataPoints[i]);
			}
			/*
			 * This section verifies DataPoints with intensity="0" and exclude
			 * them from tempDataPoints array. Only accept some of these points
			 * because they are part the left/right part of the peak.
			 */

			int i, j;
			for (i = 0, j = 0; i < completeDataPoints.length; i++) {
				float intensity = completeDataPoints[i].getIntensity();
				float mz = completeDataPoints[i].getMZ();
				if (completeDataPoints[i].getIntensity() > 0) {
					tempDataPoints[j] = new SimpleDataPoint(mz, intensity);
					j++;
					continue;
				}
				if ((i > 0) && (completeDataPoints[i - 1].getIntensity() > 0)) {
					tempDataPoints[j] = new SimpleDataPoint(mz, intensity);
					j++;
					continue;
				}
				if ((i < completeDataPoints.length - 1)
						&& (completeDataPoints[i + 1].getIntensity() > 0)) {
					tempDataPoints[j] = new SimpleDataPoint(mz, intensity);
					j++;
					continue;
				}
			}

			// If we have no peaks with intensity of 0, we assume the scan is
			// centroided

			buildingScan = new SimpleScan(scanNumber, msLevel, retentionTime,
					parentScan, precursorMz, null, new DataPoint[0], false);
			
			buildingScan.setCentroided(centroided);
			buildingScan.setPrecursorCharge(precursorCharge);
			
			if (i == j) {
				buildingScan.setCentroided(true);
				buildingScan.setDataPoints(tempDataPoints);
			} else {
				int sizeArray = j;
				DataPoint[] dataPoints = new DataPoint[j];

				System.arraycopy(tempDataPoints, 0, dataPoints, 0, sizeArray);
				buildingScan.setDataPoints(dataPoints);
			}

			/*
			 * Update of fragmentScanNumbers of each Scan in the parentStack
			 */

			for (SimpleScan currentScan : parentStack) {
				if (currentScan.getScanNumber() == buildingScan.getParentScanNumber()) {

					int[] currentFragmentScanNumbers = currentScan.getFragmentScanNumbers();
					if (currentFragmentScanNumbers != null) {
						int[] tempFragmentScanNumbers = currentFragmentScanNumbers;
						currentFragmentScanNumbers = new int[tempFragmentScanNumbers.length + 1];
						System.arraycopy(tempFragmentScanNumbers, 0, 
								currentFragmentScanNumbers, 0, 
								tempFragmentScanNumbers.length);
						currentFragmentScanNumbers[tempFragmentScanNumbers.length] = buildingScan
								.getScanNumber();
						currentScan.setFragmentScanNumbers(currentFragmentScanNumbers);
					} else {
						currentFragmentScanNumbers = new int[1];
						currentFragmentScanNumbers[0] = buildingScan.getScanNumber();
						currentScan.setFragmentScanNumbers(currentFragmentScanNumbers);
					}
				}
			}

			/*
			 * Verify the size of parentStack. The actual size of the window to
			 * cover possible candidates for fragmentScanNumber update is 10
			 * elements. 
			 */
			if (parentStack.size() > 10) {
				SimpleScan scan = parentStack.removeLast();
				try {
                    newMZmineFile.addScan(scan);
                } catch (IOException e) {
                    status = TaskStatus.ERROR;
                    errorMessage = "IO error: " + e;
                    throw new SAXException("Parsing cancelled");
                }
				parsedScans++;
			}

			parentStack.addFirst(buildingScan);
			buildingScan = null;

		}

		if (qName.equalsIgnoreCase("binaryDataArray")) {
			// clean the current char buffer for the new element
			binaryDataArrayFlag = false;
		}
		
		// <mzArrayBinary>
		if ((qName.equalsIgnoreCase("Binary")) && (mzArrayBinaryFlag)) {

			mzArrayBinaryFlag = false;
			mzDataPoints = new double[peaksCount];

			byte[] peakBytes = Base64.decode(charBuffer.toString()
					.toCharArray());

			/*
			 * This section provides support for decompression ZLIB compression library. 
			 */
			
			/*if (compressFlag){
				// Decompress the bytes
				Inflater decompresser = new Inflater();
				decompresser.setInput(peakBytes, 0, peaksBytes.length);
				byte[] resultCompressed = new byte[1024];
				byte[] resultTotal = new byte [0];
				
				try {
					int resultLength = decompresser.inflate(resultCompressed);
					while (!decompresser.finished()){
						byte buffer[] = resultTotal;
						resultTotal = new byte[resultTotal.length + resultLength];
						System.arraycopy(buffer, 0, resultTotal, 0, buffer.length);
						System.arraycopy(resultCompressed, 0, resultTotal, buffer.length, resultLength);
						resultLength = decompresser.inflate(resultCompressed);
					}
					byte buffer[] = resultTotal;
					resultTotal = new byte[resultTotal.length + resultLength];
					System.arraycopy(buffer, 0, resultTotal, 0, buffer.length);
					System.arraycopy(resultCompressed, 0, resultTotal, buffer.length, resultLength);
					decompresser.end();
					peakBytes = new byte[resultTotal.length];
					peakBytes = resultTotal;
				} 
				catch (Exception eof) {
					status = TaskStatus.ERROR;
					errorMessage = "Corrupt compressed peak";
					throw new SAXException("Parsing Cancelled");
				}
				compressFlag = false;
			}*/

			ByteBuffer currentMzBytes = ByteBuffer.wrap(peakBytes);
			//currentMzBytes = currentMzBytes.order(ByteOrder.LITTLE_ENDIAN);
			currentMzBytes = currentMzBytes.order(ByteOrder.BIG_ENDIAN);

			for (int i = 0; i < mzDataPoints.length; i++) {
				if (precision == null || precision.equals("32"))
					mzDataPoints[i] = (double) currentMzBytes.getFloat();
				else
					mzDataPoints[i] = currentMzBytes.getDouble();
			}
			charBuffer.setLength(0);
		}

		// <intenArrayBinary>
		if ((qName.equalsIgnoreCase("Binary")) && (intenArrayBinaryFlag)){

			intenArrayBinaryFlag = false;
			intensityDataPoints = new double[peaksCount];

			byte[] peakBytes = Base64.decode(charBuffer.toString()
					.toCharArray());

			/*
			 * This section provides support for decompression ZLIB compression library. 
			 */
			
			/*if (compressFlag){
				// Decompress the bytes
				Inflater decompresser = new Inflater();
				decompresser.setInput(peakBytes, 0, peaksBytes.length);
				byte[] resultCompressed = new byte[1024];
				byte[] resultTotal = new byte [0];
				
				try {
					int resultLength = decompresser.inflate(resultCompressed);
					while (!decompresser.finished()){
						byte buffer[] = resultTotal;
						resultTotal = new byte[resultTotal.length + resultLength];
						System.arraycopy(buffer, 0, resultTotal, 0, buffer.length);
						System.arraycopy(resultCompressed, 0, resultTotal, buffer.length, resultLength);
						resultLength = decompresser.inflate(resultCompressed);
					}
					byte buffer[] = resultTotal;
					resultTotal = new byte[resultTotal.length + resultLength];
					System.arraycopy(buffer, 0, resultTotal, 0, buffer.length);
					System.arraycopy(resultCompressed, 0, resultTotal, buffer.length, resultLength);
					decompresser.end();
					peakBytes = new byte[resultTotal.length];
					peakBytes = resultTotal;
				} 
				catch (Exception eof) {
					status = TaskStatus.ERROR;
					errorMessage = "Corrupt compressed peak";
					throw new SAXException("Parsing Cancelled");
				}
				compressFlag = false;
			}*/
			
			
			ByteBuffer currentIntensityBytes = ByteBuffer.wrap(peakBytes);
			//currentIntensityBytes = currentIntensityBytes.order(ByteOrder.LITTLE_ENDIAN);
			currentIntensityBytes = currentIntensityBytes.order(ByteOrder.BIG_ENDIAN);
			
			for (int i = 0; i < intensityDataPoints.length; i++) {
				if (precision == null || precision.equals("32"))
					intensityDataPoints[i] = (double) currentIntensityBytes
							.getFloat();
				else
					intensityDataPoints[i] = currentIntensityBytes
							.getFloat();
			}
			charBuffer.setLength(0);
		}
	}

	/**
	 * characters()
	 * 
	 * @see org.xml.sax.ContentHandler#characters(char[], int, int)
	 */
	public void characters(char buf[], int offset, int len) throws SAXException {
		charBuffer = charBuffer.append(buf, offset, len);
	}

	public void endDocument() throws SAXException {
		while (!parentStack.isEmpty()) {
			SimpleScan scan = parentStack.removeLast();
			try {
                newMZmineFile.addScan(scan);
            } catch (IOException e) {
                status = TaskStatus.ERROR;
                errorMessage = "IO error: " + e;
                throw new SAXException("Parsing cancelled");
            }
			parsedScans++;
		}
	}
}