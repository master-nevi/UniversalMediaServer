/*
 * PS3 Media Server, for streaming any medias to your PS3.
 * Copyright (C) 2008  A.Brochard
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; version 2
 * of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package net.pms.network;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import net.pms.PMS;
import net.pms.configuration.PmsConfiguration;
import net.pms.configuration.RendererConfiguration;
import net.pms.dlna.DLNAMediaInfo;
import net.pms.dlna.DLNAMediaSubtitle;
import net.pms.dlna.DLNAResource;
import net.pms.dlna.Range;
import net.pms.external.StartStopListenerDelegate;
import net.pms.util.StringUtil;
import net.pms.util.UMSUtils;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class handles all forms of incoming HTTP requests by constructing a proper HTTP response. 
 */
public class Request extends HTTPResource {
	private static final Logger LOGGER = LoggerFactory.getLogger(Request.class);
	private static final PmsConfiguration configuration = PMS.getConfiguration();

	private final static String CRLF = "\r\n";
	private final static String HTTP_200_OK = "HTTP/1.1 200 OK";
	private final static String HTTP_500 = "HTTP/1.1 500 Internal Server Error";
	private final static String HTTP_206_OK = "HTTP/1.1 206 Partial Content";
	private final static String HTTP_200_OK_10 = "HTTP/1.0 200 OK";
	private final static String HTTP_206_OK_10 = "HTTP/1.0 206 Partial Content";
	private final static String CONTENT_TYPE_UTF8 = "CONTENT-TYPE: text/xml; charset=\"utf-8\"";
	private final static String CONTENT_TYPE = "Content-Type: text/xml; charset=\"utf-8\"";
	private SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.US);
	private final String method;

	/**
	 * A {@link String} that contains the argument with which this {@link Request} was
	 * created. It contains a command, a unique resource id and a resource name, all
	 * separated by slashes. For example: "get/0$0$2$17/big_buck_bunny_1080p_h264.mov" or
	 * "get/0$0$2$13/thumbnail0000Sintel.2010.1080p.mkv"
	 */
	private String argument;
	private String soapaction;
	private String content;
	private OutputStream output;
	private String objectID;
	private int startingIndex;
	private int requestCount;
	private String browseFlag;

	/**
	 * When sending an input stream, the lowRange indicates which byte to start from.
	 */
	private long lowRange;
	private InputStream inputStream;
	private RendererConfiguration mediaRenderer;
	private String transferMode;
	private String contentFeatures;
	private double timeseek;
	private double timeRangeEnd;

	/**
	 * When sending an input stream, the highRange indicates which byte to stop at.
	 */
	private long highRange;
	private boolean http10;

	public RendererConfiguration getMediaRenderer() {
		return mediaRenderer;
	}

	public void setMediaRenderer(RendererConfiguration mediaRenderer) {
		this.mediaRenderer = mediaRenderer;
	}

	public InputStream getInputStream() {
		return inputStream;
	}

	/**
	 * When sending an input stream, the lowRange indicates which byte to start from.
	 * @return The byte to start from
	 */
	public long getLowRange() {
		return lowRange;
	}

	/**
	 * Set the byte from which to start when sending an input stream. This value will
	 * be used to send a CONTENT_RANGE header with the response.
	 * @param lowRange The byte to start from.
	 */
	public void setLowRange(long lowRange) {
		this.lowRange = lowRange;
	}

	public String getTransferMode() {
		return transferMode;
	}

	public void setTransferMode(String transferMode) {
		this.transferMode = transferMode;
	}

	public String getContentFeatures() {
		return contentFeatures;
	}

	public void setContentFeatures(String contentFeatures) {
		this.contentFeatures = contentFeatures;
	}

	public double getTimeseek() {
		return timeseek;
	}

	public void setTimeseek(double timeseek) {
		this.timeseek = timeseek;
	}

	public void setTimeRangeEnd(double timeRangeEnd) {
		this.timeRangeEnd = timeRangeEnd;
	}

	/**
	 * When sending an input stream, the highRange indicates which byte to stop at.
	 * @return The byte to stop at.
	 */
	public long getHighRange() {
		return highRange;
	}

	/**
	 * Set the byte at which to stop when sending an input stream. This value will
	 * be used to send a CONTENT_RANGE header with the response.
	 * @param highRange The byte to stop at.
	 */
	public void setHighRange(long highRange) {
		this.highRange = highRange;
	}

	public boolean isHttp10() {
		return http10;
	}

	public void setHttp10(boolean http10) {
		this.http10 = http10;
	}

	/**
	 * This class will construct and transmit a proper HTTP response to a given HTTP request.
	 * @param method The {@link String} that defines the HTTP method to be used.
	 * @param argument The {@link String} containing instructions for PMS. It contains a command,
	 * 		a unique resource id and a resource name, all separated by slashes.
	 */
	public Request(String method, String argument) {
		this.method = method;
		this.argument = argument;
	}

	public String getSoapaction() {
		return soapaction;
	}

	public void setSoapaction(String soapaction) {
		this.soapaction = soapaction;
	}

	public String getTextContent() {
		return content;
	}

	public void setTextContent(String content) {
		this.content = content;
	}

	/**
	 * Retrieves the HTTP method with which this {@link Request} was created.
	 * @return The (@link String} containing the HTTP method.
	 */
	public String getMethod() {
		return method;
	}

	/**
	 * Retrieves the argument with which this {@link Request} was created. It contains
	 * a command, a unique resource id and a resource name, all separated by slashes. For
	 * example: "get/0$0$2$17/big_buck_bunny_1080p_h264.mov" or "get/0$0$2$13/thumbnail0000Sintel.2010.1080p.mkv"
	 * @return The {@link String} containing the argument.
	 */
	public String getArgument() {
		return argument;
	}

	/**
	 * Construct a proper HTTP response to a received request. After the response has been
	 * created, it is sent and the resulting {@link ChannelFuture} object is returned.
	 * See <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html">RFC-2616</a>
	 * for HTTP header field definitions.
	 * @param output The {@link HttpResponse} object that will be used to construct the response.
	 * @param startStopListenerDelegate The {@link StartStopListenerDelegate} object that is used
	 * 			to notify plugins that the {@link DLNAResource} is about to start playing.
	 * @throws IOException
	 */
	public void answer(
		OutputStream output,
		StartStopListenerDelegate startStopListenerDelegate
	) throws IOException {
		this.output = output;

		long CLoverride = -2; // 0 and above are valid Content-Length values, -1 means omit
		if (lowRange != 0 || highRange != 0) {
			output(output, http10 ? HTTP_206_OK_10 : HTTP_206_OK);
		} else {
			if (soapaction != null && soapaction.contains("X_GetFeatureList")) {
				//  If we don't return a 500 error, Samsung 2012 TVs time out.
				output(output, HTTP_500);
			} else {
				output(output, http10 ? HTTP_200_OK_10 : HTTP_200_OK);
			}
		}

		StringBuilder response = new StringBuilder();
		DLNAResource dlna = null;
		boolean xbox360 = mediaRenderer.isXbox360();

		// Samsung 2012 TVs have a problematic preceding slash that needs to be removed.
		if (argument.startsWith("/")) {
			LOGGER.trace("Stripping preceding slash from: " + argument);
			argument = argument.substring(1);
		}

		if ((method.equals("GET") || method.equals("HEAD")) && argument.startsWith("console/")) {
			// Request to output a page to the HTML console.
			output(output, "Content-Type: text/html");
			response.append(HTMLConsole.servePage(argument.substring(8)));
		} else if ((method.equals("GET") || method.equals("HEAD")) && argument.startsWith("get/")) {
			// Request to retrieve a file

			/**
			 * Skip the leading "get/" and extract the resource ID from the first path element
			 * e.g. "get/0$1$5$3$4/Foo.mp4" -> "0$1$5$3$4"
			 *
			 * ExSport: I spotted on Android it is asking for "/get/0$2$4$2$1$3" which generates exception with response:
			 * "Http: Response, HTTP/1.1, Status: Internal server error, URL: /get/0$2$4$2$1$3"
			 * This should fix it
			 */
			String id = argument.substring(argument.indexOf("get/") + 4, argument.lastIndexOf('/'));

			// Some clients escape the separators in their request: unescape them.
			id = id.replace("%24", "$");

			// Retrieve the DLNAresource itself.
			List<DLNAResource> files = PMS.get().getRootFolder(mediaRenderer).getDLNAResources(id, false, 0, 0, mediaRenderer);

			if (transferMode != null) {
				output(output, "TransferMode.DLNA.ORG: " + transferMode);
			}

			if (files.size() == 1) {
				// DLNAresource was found.
				dlna = files.get(0);
				String fileName = argument.substring(argument.lastIndexOf('/') + 1);

				if (fileName.startsWith("thumbnail0000")) {
					// This is a request for a thumbnail file.
					output(output, "Content-Type: " + dlna.getThumbnailContentType());
					output(output, "Accept-Ranges: bytes");
					output(output, "Expires: " + getFUTUREDATE() + " GMT");
					output(output, "Connection: keep-alive");
					if (mediaRenderer.isMediaParserV2()) {
						dlna.checkThumbnail();
					}

					inputStream = dlna.getThumbnailInputStream();
				} else if (dlna.getMedia() != null && fileName.contains("subtitle0000")) {
					// This is a request for a subtitle file
					output(output, "Content-Type: text/plain");
					output(output, "Expires: " + getFUTUREDATE() + " GMT");
					DLNAMediaSubtitle sub = dlna.getMediaSubtitle();
					if (sub != null) {
						try {
							// XXX external file is null if the first subtitle track is embedded:
							// http://www.ps3mediaserver.org/forum/viewtopic.php?f=3&t=15805&p=75534#p75534
							if (sub.isExternal()) {
								inputStream = new FileInputStream(sub.getExternalFile());
							}
						} catch (NullPointerException npe) {
							LOGGER.trace("Could not find external subtitles: " + sub);
						}
					}
				} else {
					// This is a request for a regular file.
					String name = dlna.getDisplayName(mediaRenderer);
					if (dlna.isNoName()) {
						name = dlna.getName() + " " + dlna.getDisplayName(mediaRenderer);
					}

					inputStream = dlna.getInputStream(Range.create(lowRange, highRange, timeseek, timeRangeEnd), mediaRenderer);
					if (dlna.isResume()) {
						// Update timeseek to possibly adjusted resume time
						timeseek = dlna.getResume().getTimeOffset() / (double) 1000;
					}

					if (inputStream == null) {
						// No inputStream indicates that transcoding / remuxing probably crashed.
						LOGGER.error("There is no inputstream to return for " + name);
					} else {
						startStopListenerDelegate.start(dlna);
						output(output, "Content-Type: " + getRendererMimeType(dlna.mimeType(), mediaRenderer));

						if (dlna.getMedia() != null && !configuration.isDisableSubtitles()) {
							// Some renderers (like Samsung devices) allow a custom header for a subtitle URL
							String subtitleHttpHeader = mediaRenderer.getSubtitleHttpHeader();
							if (subtitleHttpHeader != null && !"".equals(subtitleHttpHeader)) {
								// Device allows a custom subtitle HTTP header; construct it
								DLNAMediaSubtitle sub = dlna.getMediaSubtitle();
								if (sub != null) {
									String subtitleUrl;
									String subExtension = sub.getType().getExtension();
									if (isNotBlank(subExtension)) {
										subExtension = "." + subExtension;
									}
									subtitleUrl = "http://" + PMS.get().getServer().getHost() +
										':' + PMS.get().getServer().getPort() + "/get/" +
										id + "/subtitle0000" + subExtension;

									output(output, subtitleHttpHeader + ": " + subtitleUrl);
								}
							}
						}

						PMS.get().getFrame().setStatusLine("Serving " + name);

						// Response generation:
						// We use -1 for arithmetic convenience but don't send it as a value.
						// If Content-Length < 0 we omit it, for Content-Range we use '*' to signify unspecified.

						boolean chunked = mediaRenderer.isChunkedTransfer();

						// Determine the total size. Note: when transcoding the length is
						// not known in advance, so DLNAMediaInfo.TRANS_SIZE will be returned instead.

						long totalsize = dlna.length(mediaRenderer);

						if (chunked && totalsize == DLNAMediaInfo.TRANS_SIZE) {
							// In chunked mode we try to avoid arbitrary values.
							totalsize = -1;
						}

						long remaining = totalsize - lowRange;
						long requested = highRange - lowRange;

						if (requested != 0) {
							// Determine the range (i.e. smaller of known or requested bytes)
							long bytes = remaining > -1 ? remaining : inputStream.available();

							if (requested > 0 && bytes > requested) {
								bytes = requested + 1;
							}

							// Calculate the corresponding highRange (this is usually redundant).
							highRange = lowRange + bytes - (bytes > 0 ? 1 : 0);

							LOGGER.trace((chunked ? "Using chunked response. " : "") + "Sending " + bytes + " bytes.");

							output(output, "Content-Range: bytes " + lowRange + "-" + (highRange > -1 ? highRange : "*") + "/" + (totalsize > -1 ? totalsize : "*"));

							// Content-Length refers to the current chunk size here, though in chunked
							// mode if the request is open-ended and totalsize is unknown we omit it.
							if (chunked && requested < 0 && totalsize < 0) {
								CLoverride = -1;
							} else {
								CLoverride = bytes;
							}
						} else {
							// Content-Length refers to the total remaining size of the stream here.
							CLoverride = remaining;
						}

						if (contentFeatures != null) {
							output(output, "ContentFeatures.DLNA.ORG: " + dlna.getDlnaContentFeatures(mediaRenderer));
						}

						if (dlna.getPlayer() == null || xbox360) {
							output(output, "Accept-Ranges: bytes");
						}

						output(output, "Connection: keep-alive");
					}
				}
			}
		} else if ((method.equals("GET") || method.equals("HEAD")) && (argument.toLowerCase().endsWith(".png") || argument.toLowerCase().endsWith(".jpg") || argument.toLowerCase().endsWith(".jpeg"))) {
			if (argument.toLowerCase().endsWith(".png")) {
				output(output, "Content-Type: image/png");
			} else {
				output(output, "Content-Type: image/jpeg");
			}

			output(output, "Accept-Ranges: bytes");
			output(output, "Connection: keep-alive");
			output(output, "Expires: " + getFUTUREDATE() + " GMT");
			inputStream = getResourceInputStream(argument);
		} else if ((method.equals("GET") || method.equals("HEAD")) && (argument.equals("description/fetch") || argument.endsWith("1.0.xml"))) {
			String profileName = "";
			if (configuration.isAppendProfileName()) {
				profileName = " [" + configuration.getProfileName() + "]";
			}

			String serverName = configuration.getServerName();
			output(output, CONTENT_TYPE);
			output(output, "Cache-Control: no-cache");
			output(output, "Expires: 0");
			output(output, "Accept-Ranges: bytes");
			output(output, "Connection: keep-alive");
			inputStream = getResourceInputStream((argument.equals("description/fetch") ? "PMS.xml" : argument));

			if (argument.equals("description/fetch")) {
				byte b[] = new byte[inputStream.available()];
				inputStream.read(b);
				String s = new String(b);
				s = s.replace("[uuid]", PMS.get().usn()); //.substring(0, PMS.get().usn().length()-2));

				if (PMS.get().getServer().getHost() != null) {
					s = s.replace("[host]", PMS.get().getServer().getHost());
					s = s.replace("[port]", "" + PMS.get().getServer().getPort());
				}

				if (xbox360) {
					LOGGER.debug("DLNA changes for Xbox 360");
					s = s.replace("Universal Media Server", serverName + profileName + " : Windows Media Connect");
					s = s.replace("<modelName>UMS</modelName>", "<modelName>Windows Media Connect</modelName>");
					s = s.replace("<serviceList>", "<serviceList>" + CRLF + "<service>" + CRLF +
						"<serviceType>urn:microsoft.com:service:X_MS_MediaReceiverRegistrar:1</serviceType>" + CRLF +
						"<serviceId>urn:microsoft.com:serviceId:X_MS_MediaReceiverRegistrar</serviceId>" + CRLF +
						"<SCPDURL>/upnp/mrr/scpd</SCPDURL>" + CRLF +
						"<controlURL>/upnp/mrr/control</controlURL>" + CRLF +
						"</service>" + CRLF);
				} else {
					s = s.replace("Universal Media Server", serverName + profileName);
				}

				inputStream = new ByteArrayInputStream(s.getBytes());
			}
		} else if (method.equals("POST") && (argument.contains("MS_MediaReceiverRegistrar_control") || argument.contains("mrr/control"))) {
			output(output, CONTENT_TYPE_UTF8);
			response.append(HTTPXMLHelper.XML_HEADER);
			response.append(CRLF);
			response.append(HTTPXMLHelper.SOAP_ENCODING_HEADER);
			response.append(CRLF);

			if (soapaction != null && soapaction.contains("IsAuthorized")) {
				response.append(HTTPXMLHelper.XBOX_360_2);
				response.append(CRLF);
			} else if (soapaction != null && soapaction.contains("IsValidated")) {
				response.append(HTTPXMLHelper.XBOX_360_1);
				response.append(CRLF);
			}

			response.append(HTTPXMLHelper.SOAP_ENCODING_FOOTER);
			response.append(CRLF);
		} else if (method.equals("POST") && argument.endsWith("upnp/control/connection_manager")) {
			output(output, CONTENT_TYPE_UTF8);
			if (soapaction != null && soapaction.contains("ConnectionManager:1#GetProtocolInfo")) {
				response.append(HTTPXMLHelper.XML_HEADER);
				response.append(CRLF);
				response.append(HTTPXMLHelper.SOAP_ENCODING_HEADER);
				response.append(CRLF);
				response.append(HTTPXMLHelper.PROTOCOLINFO_RESPONSE);
				response.append(CRLF);
				response.append(HTTPXMLHelper.SOAP_ENCODING_FOOTER);
				response.append(CRLF);
			}
		} else if (method.equals("POST") && argument.endsWith("upnp/control/content_directory")) {
			output(output, CONTENT_TYPE_UTF8);

			if (soapaction != null && soapaction.contains("ContentDirectory:1#GetSystemUpdateID")) {
				response.append(HTTPXMLHelper.XML_HEADER);
				response.append(CRLF);
				response.append(HTTPXMLHelper.SOAP_ENCODING_HEADER);
				response.append(CRLF);
				response.append(HTTPXMLHelper.GETSYSTEMUPDATEID_HEADER);
				response.append(CRLF);
				response.append("<Id>").append(DLNAResource.getSystemUpdateId()).append("</Id>");
				response.append(CRLF);
				response.append(HTTPXMLHelper.GETSYSTEMUPDATEID_FOOTER);
				response.append(CRLF);
				response.append(HTTPXMLHelper.SOAP_ENCODING_FOOTER);
				response.append(CRLF);
			} else if (soapaction != null && soapaction.contains("ContentDirectory:1#GetSortCapabilities")) {
				response.append(HTTPXMLHelper.XML_HEADER);
				response.append(CRLF);
				response.append(HTTPXMLHelper.SOAP_ENCODING_HEADER);
				response.append(CRLF);
				response.append(HTTPXMLHelper.SORTCAPS_RESPONSE);
				response.append(CRLF);
				response.append(HTTPXMLHelper.SOAP_ENCODING_FOOTER);
				response.append(CRLF);
			} else if (soapaction != null && soapaction.contains("ContentDirectory:1#X_GetFeatureList")) { // Added for Samsung 2012 TVs
				response.append(HTTPXMLHelper.XML_HEADER);
				response.append(CRLF);
				response.append(HTTPXMLHelper.SOAP_ENCODING_HEADER);
				response.append(CRLF);
				response.append(HTTPXMLHelper.SAMSUNG_ERROR_RESPONSE);
				response.append(CRLF);
				response.append(HTTPXMLHelper.SOAP_ENCODING_FOOTER);
				response.append(CRLF);
			} else if (soapaction != null && soapaction.contains("ContentDirectory:1#GetSearchCapabilities")) {
				response.append(HTTPXMLHelper.XML_HEADER);
				response.append(CRLF);
				response.append(HTTPXMLHelper.SOAP_ENCODING_HEADER);
				response.append(CRLF);
				response.append(HTTPXMLHelper.SEARCHCAPS_RESPONSE);
				response.append(CRLF);
				response.append(HTTPXMLHelper.SOAP_ENCODING_FOOTER);
				response.append(CRLF);
			} else if (soapaction != null && (soapaction.contains("ContentDirectory:1#Browse") || soapaction.contains("ContentDirectory:1#Search"))) {
				objectID = getEnclosingValue(content, "<ObjectID", "</ObjectID>");
				String containerID = null;
				if ((objectID == null || objectID.length() == 0)) {
					containerID = getEnclosingValue(content, "<ContainerID", "</ContainerID>");
					if (containerID == null || !containerID.contains("$")) {
						objectID = "0";
					} else {
						objectID = containerID;
						containerID = null;
					}
				}
				Object sI = getEnclosingValue(content, "<StartingIndex", "</StartingIndex>");
				Object rC = getEnclosingValue(content, "<RequestedCount", "</RequestedCount>");
				browseFlag = getEnclosingValue(content, "<BrowseFlag", "</BrowseFlag>");

				if (sI != null) {
					startingIndex = Integer.parseInt(sI.toString());
				}

				if (rC != null) {
					requestCount = Integer.parseInt(rC.toString());
				}

				response.append(HTTPXMLHelper.XML_HEADER);
				response.append(CRLF);
				response.append(HTTPXMLHelper.SOAP_ENCODING_HEADER);
				response.append(CRLF);

				if (soapaction != null && soapaction.contains("ContentDirectory:1#Search")) {
					response.append(HTTPXMLHelper.SEARCHRESPONSE_HEADER);
				} else {
					response.append(HTTPXMLHelper.BROWSERESPONSE_HEADER);
				}

				response.append(CRLF);
				response.append(HTTPXMLHelper.RESULT_HEADER);
				response.append(HTTPXMLHelper.DIDL_HEADER);

				boolean browseDirectChildren = browseFlag != null && browseFlag.equals("BrowseDirectChildren");

				if (soapaction != null && soapaction.contains("ContentDirectory:1#Search")) {
					browseDirectChildren = true;
				}

				// Xbox 360 virtual containers ... d'oh!
				String searchCriteria = null;
				if (xbox360 && configuration.getUseCache() && PMS.get().getLibrary() != null && containerID != null) {
					if (containerID.equals("7") && PMS.get().getLibrary().getAlbumFolder() != null) {
						objectID = PMS.get().getLibrary().getAlbumFolder().getResourceId();
					} else if (containerID.equals("6") && PMS.get().getLibrary().getArtistFolder() != null) {
						objectID = PMS.get().getLibrary().getArtistFolder().getResourceId();
					} else if (containerID.equals("5") && PMS.get().getLibrary().getGenreFolder() != null) {
						objectID = PMS.get().getLibrary().getGenreFolder().getResourceId();
					} else if (containerID.equals("F") && PMS.get().getLibrary().getPlaylistFolder() != null) {
						objectID = PMS.get().getLibrary().getPlaylistFolder().getResourceId();
					} else if (containerID.equals("4") && PMS.get().getLibrary().getAllFolder() != null) {
						objectID = PMS.get().getLibrary().getAllFolder().getResourceId();
					} else if (containerID.equals("1")) {
						String artist = getEnclosingValue(content, "upnp:artist = &quot;", "&quot;)");
						if (artist != null) {
							objectID = PMS.get().getLibrary().getArtistFolder().getResourceId();
							searchCriteria = artist;
						}
					}
				} else if (soapaction.contains("ContentDirectory:1#Search")) {
					searchCriteria = getEnclosingValue(content, "<SearchCriteria", "</SearchCriteria>");
				}

				List<DLNAResource> files = PMS.get().getRootFolder(mediaRenderer).getDLNAResources(
					objectID,
					browseDirectChildren,
					startingIndex,
					requestCount,
					mediaRenderer,
					searchCriteria
				);

				if (searchCriteria != null && files != null) {
					UMSUtils.postSearch(files, searchCriteria);
					if (xbox360) {
						if (files.size() > 0) {
							files = files.get(0).getChildren();
						}
					}
				}

				int minus = 0;
				if (files != null) {
					for (DLNAResource uf : files) {
						if (xbox360 && containerID != null) {
							uf.setFakeParentId(containerID);
						}

						if (uf.isCompatible(mediaRenderer) && (uf.getPlayer() == null || uf.getPlayer().isPlayerCompatible(mediaRenderer))) {
							response.append(uf.getDidlString(mediaRenderer));
						} else {
							minus++;
						}
					}
				}

				response.append(HTTPXMLHelper.DIDL_FOOTER);
				response.append(HTTPXMLHelper.RESULT_FOOTER);
				response.append(CRLF);

				int filessize = 0;
				if (files != null) {
					filessize = files.size();
				}

				response.append("<NumberReturned>").append(filessize - minus).append("</NumberReturned>");
				response.append(CRLF);
				DLNAResource parentFolder = null;

				if (files != null && filessize > 0) {
					parentFolder = files.get(0).getParent();
				}

				if (browseDirectChildren && mediaRenderer.isMediaParserV2() && mediaRenderer.isDLNATreeHack()) {
					// with the new parser, files are parsed and analyzed *before*
					// creating the DLNA tree, every 10 items (the ps3 asks 10 by 10),
					// so we do not know exactly the total number of items in the DLNA folder to send
					// (regular files, plus the #transcode folder, maybe the #imdb one, also files can be
					// invalidated and hidden if format is broken or encrypted, etc.).
					// let's send a fake total size to force the renderer to ask following items
					int totalCount = startingIndex + requestCount + 1; // returns 11 when 10 asked

					// If no more elements, send the startingIndex
					if (filessize - minus <= 0) {
						totalCount = startingIndex;
					}

					response.append("<TotalMatches>").append(totalCount).append("</TotalMatches>");
				} else if (browseDirectChildren) {
					response.append("<TotalMatches>").append(((parentFolder != null) ? parentFolder.childrenNumber() : filessize) - minus).append("</TotalMatches>");
				} else {
					// From upnp spec: If BrowseMetadata is specified in the BrowseFlags then TotalMatches = 1
					response.append("<TotalMatches>1</TotalMatches>");
				}

				response.append(CRLF);
				response.append("<UpdateID>");

				if (parentFolder != null) {
					response.append(parentFolder.getUpdateId());
				} else {
					response.append("1");
				}

				response.append("</UpdateID>");
				response.append(CRLF);
				if (soapaction != null && soapaction.contains("ContentDirectory:1#Search")) {
					response.append(HTTPXMLHelper.SEARCHRESPONSE_FOOTER);
				} else {
					response.append(HTTPXMLHelper.BROWSERESPONSE_FOOTER);
				}
				response.append(CRLF);
				response.append(HTTPXMLHelper.SOAP_ENCODING_FOOTER);
				response.append(CRLF);
				//LOGGER.trace(response.toString());
			}
		} else if (method.equals("SUBSCRIBE")) {
			if (soapaction == null) {
				// Ignore this
				return;
			}
			output(output, CONTENT_TYPE_UTF8);
			output(output, "Content-Length: 0");
			output(output, "Connection: close");
			output(output, "SID: " + PMS.get().usn());
			output(output, "Server: " + PMS.get().getServerName());
			output(output, "Timeout: Second-1800");
			output(output, "");
			output.flush();

			if (soapaction != null) {
				String cb = soapaction.replace("<", "").replace(">", "");

				try {
					URL soapActionUrl = new URL(cb);
					String addr = soapActionUrl.getHost();
					int port = soapActionUrl.getPort();
					try (Socket sock = new Socket(addr,port)) {
						OutputStream out = sock.getOutputStream();

						output(out, "NOTIFY /" + argument + " HTTP/1.1");
						output(out, "SID: " + PMS.get().usn());
						output(out, "SEQ: " + 0);
						output(out, "NT: upnp:event");
						output(out, "NTS: upnp:propchange");
						output(out, "HOST: " + addr + ":" + port);
						output(out, CONTENT_TYPE_UTF8);
					}
				} catch (MalformedURLException ex) {
					LOGGER.debug("Cannot parse address and port from soap action \"" + soapaction + "\"", ex);
				}
			} else {
				LOGGER.debug("Expected soap action in request");
			}

			if (argument.contains("connection_manager")) {
				response.append(HTTPXMLHelper.eventHeader("urn:schemas-upnp-org:service:ConnectionManager:1"));
				response.append(HTTPXMLHelper.eventProp("SinkProtocolInfo"));
				response.append(HTTPXMLHelper.eventProp("SourceProtocolInfo"));
				response.append(HTTPXMLHelper.eventProp("CurrentConnectionIDs"));
				response.append(HTTPXMLHelper.EVENT_FOOTER);
			} else if (argument.contains("content_directory")) {
				response.append(HTTPXMLHelper.eventHeader("urn:schemas-upnp-org:service:ContentDirectory:1"));
				response.append(HTTPXMLHelper.eventProp("TransferIDs"));
				response.append(HTTPXMLHelper.eventProp("ContainerUpdateIDs"));
				response.append(HTTPXMLHelper.eventProp("SystemUpdateID", "" + DLNAResource.getSystemUpdateId()));
				response.append(HTTPXMLHelper.EVENT_FOOTER);
			}
		}

		output(output, "Server: " + PMS.get().getServerName());

		if (response.length() > 0) {
			// A response message was constructed; convert it to data ready to be sent.
			byte responseData[] = response.toString().getBytes("UTF-8");
			output(output, "Content-Length: " + responseData.length);
			output(output, "");

			// HEAD requests only require headers to be set, no need to set contents.
			if (!method.equals("HEAD")) {
				output.write(responseData);
				//LOGGER.trace(response.toString());
			}
		} else if (inputStream != null) {
			// There is an input stream to send as a response.

			if (CLoverride > -2) {
				// Content-Length override has been set, send or omit as appropriate
				if (CLoverride > -1 && CLoverride != DLNAMediaInfo.TRANS_SIZE) {
					// Since PS3 firmware 2.50, it is wiser not to send an arbitrary Content-Length,
					// as the PS3 will display a network error and request the last seconds of the
					// transcoded video. Better to send no Content-Length at all.
					output(output, "Content-Length: " + CLoverride);
				}
			} else {
				int cl = inputStream.available();
				LOGGER.trace("Available Content-Length: " + cl);
				output(output, "Content-Length: " + cl);
			}

			if (timeseek > 0 && dlna != null) {
				// Add timeseek information headers.
				String timeseekValue = StringUtil.convertTimeToString(timeseek, StringUtil.DURATION_TIME_FORMAT);
				String timetotalValue = dlna.getMedia().getDurationString();
				output(output, "TimeSeekRange.dlna.org: npt=" + timeseekValue + "-" + timetotalValue + "/" + timetotalValue);
				output(output, "X-Seek-Range: npt=" + timeseekValue + "-" + timetotalValue + "/" + timetotalValue);
			}

			// Send the response headers to the client.
			output(output, "");
			long sendB = 0;

			if (lowRange != DLNAMediaInfo.ENDFILE_POS && !method.equals("HEAD")) {
				sendB = sendBytes(inputStream); //, ((lowRange > 0 && highRange > 0)?(highRange-lowRange):-1)
			}

			LOGGER.trace("Sending stream: " + sendB + " bytes of " + argument);
			PMS.get().getFrame().setStatusLine(null);
		} else { // inputStream is null
			if (lowRange > 0 && highRange > 0) {
				output(output, "Content-Length: " + (highRange - lowRange + 1));
			} else {
				output(output, "Content-Length: 0");
			}

			output(output, "");
		}
	}

	private void output(OutputStream output, String line) throws IOException {
		output.write((line + CRLF).getBytes("UTF-8"));
		LOGGER.trace("Wrote on socket: " + line);
	}

	private String getFUTUREDATE() {
		sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
		return sdf.format(new Date(10000000000L + System.currentTimeMillis()));
	}

	// VISTA tip ?: netsh interface tcp set global autotuninglevel=disabled
	private int sendBytes(InputStream fis) throws IOException {
		byte[] buffer = new byte[32 * 1024];
		int bytes;
		int sendBytes = 0;

		try {
			while ((bytes = fis.read(buffer)) != -1) {
				output.write(buffer, 0, bytes);
				sendBytes += bytes;
			}
		} catch (IOException e) {
			LOGGER.trace("Sending stream with premature end: " + sendBytes + " bytes of " + argument + ". Reason: " + e.getMessage());
		} finally {
			fis.close();
		}

		return sendBytes;
	}

	/**
	 * Returns the string value that is enclosed by the left and right tag in a content string.
	 * Only the first match of each tag is used to determine positions. If either of the tags
	 * cannot be found, null is returned.
	 * @param content The entire {@link String} that needs to be searched for the left and right tag. 
	 * @param leftTag The {@link String} determining the match for the left tag. 
	 * @param rightTag The {@link String} determining the match for the right tag.
	 * @return The {@link String} that was enclosed by the left and right tag.
	 */
	private String getEnclosingValue(String content, String leftTag, String rightTag) {
		String result = null;
		int leftTagPos = content.indexOf(leftTag);
		int leftTagStop = content.indexOf(">", leftTagPos + 1);
		int rightTagPos = content.indexOf(rightTag, leftTagStop + 1);

		if (leftTagPos > -1 && rightTagPos > leftTagPos) {
			result = content.substring(leftTagStop + 1, rightTagPos);
		}

		return result;
	}
}
