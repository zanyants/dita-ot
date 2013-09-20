/*
 * This file is part of the DITA Open Toolkit project.
 * See the accompanying license.txt file for applicable licenses.
 */

/*
 * (c) Copyright IBM Corp. 2007 All Rights Reserved.
 */
package org.dita.dost.writer;

import static org.dita.dost.module.GenMapAndTopicListModule.*;
import static org.dita.dost.reader.ChunkMapReader.*;
import static org.dita.dost.util.Constants.*;
import static org.dita.dost.util.FileUtils.*;
import static org.dita.dost.util.URLUtils.toFile;
import static org.dita.dost.writer.DitaWriter.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import org.dita.dost.exception.DITAOTException;
import org.dita.dost.exception.DITAOTXMLErrorHandler;
import org.dita.dost.log.MessageUtils;
import org.dita.dost.module.ChunkModule.ChunkFilenameGenerator;
import org.dita.dost.module.Content;
import org.dita.dost.util.DITAAttrUtils;
import org.dita.dost.util.FileUtils;
import org.dita.dost.util.Job;
import org.dita.dost.util.StringUtils;
import org.dita.dost.util.TopicIdParser;
import org.dita.dost.util.XMLUtils;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.AttributesImpl;

/**
 * ChunkTopicParser class, writing chunking content into relative topic files
 * and then update list. Not reusable and not thread-safe.
 * 
 * <p>
 * TODO: Refactor to be a SAX filter.
 * </p>
 */
public final class ChunkTopicParser extends AbstractXMLWriter {

    private static final String ATTR_CHUNK_VALUE_SELECT_BRANCH = "select-branch";
    private static final String ATTR_CHUNK_VALUE_TO_CONTENT = "to-content";
    private static final String ATTR_CHUNK_VALUE_SELECT_TOPIC = "select-topic";
    private static final String ATTR_CHUNK_VALUE_SELECT_DOCUMENT = "select-document";
    private LinkedHashMap<String, String> changeTable = null;
    private Hashtable<String, String> conflictTable = null;

    private Element elem = null;

    private Element topicDoc = null;

    private boolean separate = false;
    /** Input file's parent directory */
    private File filePath = null;

    private String currentParsingFile = null;
    private String outputFile = null;
    private final Stack<String> outputFileNameStack;

    private String targetTopicId = null;

    private String selectMethod = ATTR_CHUNK_VALUE_SELECT_DOCUMENT;
    // flag whether output the nested nodes
    private boolean include = false;
    private boolean skip = false;

    private int includelevel = 0;
    private int skipLevel = 0;

    private final Set<String> topicSpecSet;

    private boolean startFromFirstTopic = false;

    private final XMLReader reader;
    private Writer output = null;

    private final Stack<Writer> fileWriterStack;
    private final Stack<Element> stubStack;

    // stub is used as the anchor to mark where to insert generated child
    // topicref inside current topicref
    private Element stub = null;

    // siblingStub is similar to stub. The only different is it is used to
    // insert generated topicref sibling to current topicref
    private Element siblingStub = null;

    private Set<String> topicID;

    private final Set<String> copyto;

    private final Set<String> copytoSource;

    private final Map<File, File> copytotarget2source;

    private Map<String, String> currentParsingFileTopicIDChangeTable;

    private static final String ditaarchNSValue = "http://dita.oasis-open.org/architecture/2005/";

    private ChunkFilenameGenerator chunkFilenameGenerator;

    /**
     * Constructor.
     */
    public ChunkTopicParser() {
        super();
        topicSpecSet = new HashSet<String>(INT_16);
        fileWriterStack = new Stack<Writer>();
        stubStack = new Stack<Element>();
        outputFileNameStack = new Stack<String>();
        topicID = new HashSet<String>();
        copyto = new HashSet<String>();
        copytoSource = new HashSet<String>();
        copytotarget2source = new HashMap<File, File>();
        try {
            reader = StringUtils.getXMLReader();
            reader.setContentHandler(this);
            reader.setProperty(LEXICAL_HANDLER_PROPERTY, this);
            reader.setFeature(FEATURE_NAMESPACE_PREFIX, true);
            reader.setFeature("http://apache.org/xml/features/scanner/notify-char-refs", true);
            reader.setFeature("http://apache.org/xml/features/scanner/notify-builtin-refs", true);
        } catch (final Exception e) {
            throw new RuntimeException("Failed to initialize XML parser: " + e.getMessage(), e);
        }
    }

    @Override
    public void characters(final char[] ch, final int start, final int length) throws SAXException {
        if (include) {
            try {
                output.write(StringUtils.escapeXML(ch, start, length));
            } catch (final Exception e) {
                logger.logError(e.getMessage(), e);
            }
        }
    }

    @Override
    public void comment(final char[] ch, final int start, final int length) throws SAXException {
        super.comment(ch, start, length);
    }

    @Override
    public void endDocument() throws SAXException {
        include = false;
        skip = false;
    }

    @Override
    public void endElement(final String uri, final String localName, final String qName) throws SAXException {
        if (skip && skipLevel > 0) {
            skipLevel--;
        } else if (skip) {
            include = true;
            skip = false;
            skipLevel = 0;
        }

        if (include) {
            try {
                includelevel--;
                if (includelevel >= 0) {
                    // prevent adding </dita> into output
                    writeEndElement(output, qName);
                }
                if (includelevel == 0 && !ATTR_CHUNK_VALUE_SELECT_DOCUMENT.equals(selectMethod)) {
                    include = false;
                }
                if (topicSpecSet.contains(qName) && separate && !fileWriterStack.isEmpty()) {
                    // if it is end of topic and separate is true
                    try {
                        output.close();
                    } catch (final IOException e) {
                        logger.logError(e.getMessage(), e);
                    }
                    output = fileWriterStack.pop();
                    outputFile = outputFileNameStack.pop();
                    stub.getParentNode().removeChild(stub);
                    stub = stubStack.pop();
                }
            } catch (final Exception e) {
                logger.logError(e.getMessage(), e);
            }
        }

    }

    @Override
    public void endPrefixMapping(final String prefix) throws SAXException {
        super.endPrefixMapping(prefix);
    }

    @Override
    public void ignorableWhitespace(final char[] ch, final int start, final int length) throws SAXException {
        if (include) {
            try {
                output.write(ch, start, length);
            } catch (final Exception e) {
                logger.logError(e.getMessage(), e);
            }
        }
    }

    @Override
    public void processingInstruction(final String target, final String data) throws SAXException {
        if (include || PI_WORKDIR_TARGET.equalsIgnoreCase(target) || PI_WORKDIR_TARGET_URI.equals(target)
                || PI_PATH2PROJ_TARGET.equalsIgnoreCase(target)) {
            try {
                writeProcessingInstruction(output, target, data);
            } catch (final Exception e) {
                logger.logError(e.getMessage(), e);
            }
        }
    }

    @Override
    public void setContent(final Content content) {
        // NOOP
    }

    @Override
    public void skippedEntity(final String name) throws SAXException {
        if (include) {
            try {
                output.write(StringUtils.getEntity(name));
            } catch (final Exception e) {
                logger.logError(e.getMessage(), e);
            }
        }
    }

    @Override
    public void startDocument() throws SAXException {
        // dontWriteDita = true;
        // difference between to-content & select-topic
        if (ATTR_CHUNK_VALUE_SELECT_DOCUMENT.equals(selectMethod)) {
            // currentParsingFile can never equal outputFile except when
            // chunk="to-content"
            // is set at map level
            // TODO former is set and line 606(895) and the later is set at line
            // 838
            if ((currentParsingFile).equals(outputFile)) {
                // if current file serves as root of new chunk
                // include will be set to true in startDocument()
                // in order to copy PIs and <dita> element
                // otherwise, if current file is copied to other file
                // do not copy PIs and <dita>element

                include = true;
                skip = false;
                skipLevel = 0;
            } else {
                include = false;
                startFromFirstTopic = true;
                skip = false;
                skipLevel = 0;
            }
        }

    }

    @Override
    public void startElement(final String uri, final String localName, final String qName, final Attributes atts)
            throws SAXException {
        final String classValue = atts.getValue(ATTRIBUTE_NAME_CLASS);
        final String idValue = atts.getValue(ATTRIBUTE_NAME_ID);

        if (skip && skipLevel > 0) {
            skipLevel++;
        }

        try {
            if (classValue != null && TOPIC_TOPIC.matches(classValue)) {
                topicSpecSet.add(qName);
                final String id = atts.getValue(ATTRIBUTE_NAME_ID);
                // search node by id.
                final Element element = DITAAttrUtils.getInstance().searchForNode(topicDoc, id, ATTRIBUTE_NAME_ID,
                        TOPIC_TOPIC.matcher);

                // only by topic
                if (separate && include && !ATTR_CHUNK_VALUE_SELECT_TOPIC.equals(selectMethod)) {
                    // chunk="by-topic" and next topic element found
                    fileWriterStack.push(output);
                    outputFileNameStack.push(outputFile);

                    // need generate new file based on new topic id
                    String newFileName = FileUtils.resolveFile(filePath, idValue + FILE_EXTENSION_DITA).getPath();
                    if (StringUtils.isEmptyString(idValue) || FileUtils.fileExists(newFileName)) {
                        final String t = newFileName;
                        newFileName = FileUtils.resolveFile(filePath, generateFilename()).getPath();
                        conflictTable.put(newFileName, t);
                    }
                    outputFile = newFileName;
                    output = new OutputStreamWriter(new FileOutputStream(newFileName), UTF8);
                    // write xml header and workdir PI to the new generated file
                    writeStartDocument(output);
                    if (OS_NAME.toLowerCase().indexOf(OS_NAME_WINDOWS) == -1) {
                        writeProcessingInstruction(output, PI_WORKDIR_TARGET, filePath.getAbsolutePath());
                    } else {
                        writeProcessingInstruction(output, PI_WORKDIR_TARGET, UNIX_SEPARATOR + filePath);
                    }
                    writeProcessingInstruction(output, PI_WORKDIR_TARGET_URI, filePath.toURI().toString());
                    changeTable.put(newFileName, newFileName);
                    if (idValue != null) {
                        changeTable.put(setFragment(currentParsingFile, idValue), newFileName + SHARP + idValue);
                    } else {
                        changeTable.put(currentParsingFile, newFileName);
                    }
                    // create a new child element
                    // in separate case elem is equals to parameter
                    // element in separateChunk(Element element)
                    final Element newChild = elem.getOwnerDocument().createElement(MAP_TOPICREF.localName);
                    newChild.setAttribute(ATTRIBUTE_NAME_HREF, FileUtils.getRelativeUnixPath(filePath + UNIX_SEPARATOR
                            + FILE_NAME_STUB_DITAMAP, newFileName));

                    newChild.setAttribute(ATTRIBUTE_NAME_CLASS, MAP_TOPICREF.toString());
                    newChild.setAttribute(ATTRIBUTE_NAME_XTRF, ATTR_XTRF_VALUE_GENERATED);

                    createTopicMeta(element, newChild);

                    if (stub != null) {
                        if (includelevel == 0 && siblingStub != null) {
                            // if it is the following sibling topic to the first
                            // topic in ditabase
                            // The first topic will not enter the logic at here
                            // because when meeting
                            // with first topic in ditabase, the include value
                            // is false
                            siblingStub.getParentNode().insertBefore(newChild, siblingStub);

                        } else {
                            stub.getParentNode().insertBefore(newChild, stub);
                        }
                        // <element>
                        // <newchild/>
                        // <stub/>
                        // <stub/>
                        // ...
                        // </element>
                        // <siblingstub/>
                        // ...
                        stubStack.push(stub);
                        stub = (Element) stub.cloneNode(false);
                        newChild.appendChild(stub);
                    }
                }
                if (include && ATTR_CHUNK_VALUE_SELECT_TOPIC.equals(selectMethod)) {
                    // if select method is "select-topic" and
                    // current topic is the nested topic in
                    // target topic-->skip it.
                    include = false;
                    skipLevel = 1;
                    skip = true;
                    // Fix: no need to close the tag, just skip the nested
                    // topic.
                    // output.write("</"+qName+">");
                } else if (include) {
                    // if select method is "select-document" or "select-branch"
                    // and current topic is the nested topic in target topic.
                    // if file name has been changed, add an entry in
                    // changeTable
                    if (!currentParsingFile.equals(outputFile)) {
                        if (idValue != null) {
                            changeTable.put(currentParsingFile + SHARP + idValue, outputFile + SHARP + idValue);
                        } else {
                            changeTable.put(currentParsingFile, outputFile);
                        }
                    }
                } else if (skip) {
                    skipLevel = 1;
                } else if (!include && idValue != null && (idValue.equals(targetTopicId) || startFromFirstTopic)) {
                    // if the target topic has not been found and
                    // current topic is the target topic
                    include = true;
                    includelevel = 0;
                    skip = false;
                    skipLevel = 0;
                    startFromFirstTopic = false;
                    if (!currentParsingFile.equals(outputFile)) {
                        changeTable.put(currentParsingFile + SHARP + idValue, outputFile + SHARP + idValue);
                    }
                }
            }

            if (include) {
                includelevel++;
                final AttributesImpl resAtts = new AttributesImpl(atts);
                for (int i = 0; i < resAtts.getLength(); i++) {
                    final String attrName = resAtts.getQName(i);
                    String attrValue = resAtts.getValue(i);

                    if (ATTRIBUTE_NAME_ID.equals(attrName) && TOPIC_TOPIC.matches(classValue)) {
                        // change topic @id if there are conflicts.
                        if (topicID.contains(attrValue)) {
                            final String oldAttrValue = attrValue;
                            attrValue = chunkFilenameGenerator.generateID();
                            topicID.add(attrValue);

                            String tmpVal = changeTable.get(currentParsingFile + SHARP + idValue);
                            if (tmpVal != null && tmpVal.equalsIgnoreCase(outputFile + SHARP + idValue)) {
                                changeTable.put(currentParsingFile + SHARP + idValue, outputFile + SHARP + attrValue);
                            }

                            tmpVal = changeTable.get(currentParsingFile);
                            if (tmpVal != null && tmpVal.equalsIgnoreCase(outputFile + SHARP + idValue)) {
                                changeTable.put(currentParsingFile, outputFile + SHARP + attrValue);
                            }
                            currentParsingFileTopicIDChangeTable.put(oldAttrValue, attrValue);
                        } else {
                            topicID.add(attrValue);
                        }
                    }
                    String value = attrValue;
                    if (ATTRIBUTE_NAME_HREF.equals(attrName)) {
                        // update @href value
                        if (checkHREF(resAtts)) {
                            // if current @href value needs to be updated
                            String relative = FileUtils.getRelativeUnixPath(outputFile, currentParsingFile);
                            if (conflictTable.containsKey(outputFile)) {
                                final String realoutputfile = conflictTable.get(outputFile);
                                relative = FileUtils.getRelativeUnixPath(realoutputfile, currentParsingFile);
                            }
                            if (attrValue.startsWith(SHARP)) {
                                // if @href refers to a location inside current
                                // parsing file
                                // update @href to point back to current file
                                // if the location is moved to chunk, @href will
                                // be update again
                                // to the new location.
                                value = relative + attrValue;
                            } else if (relative.indexOf(SLASH) != -1) {
                                // if new file is not under the same directory
                                // with current file
                                // add path information to the @href value
                                relative = relative.substring(0, relative.lastIndexOf(SLASH));
                                value = FileUtils.resolveTopic(relative, attrValue);
                            } else {
                                // if new file is under the same directory with
                                // current file
                                // do not update the @href value
                            }
                        } else {
                            // if current @href value does not need to be
                            // updated
                        }
                    }
                    resAtts.setValue(i, value);
                }

                if (classValue != null && TOPIC_TOPIC.matches(classValue) && resAtts.getValue("xmlns:ditaarch") == null) {
                    // if there is none declaration for ditaarch namespace,
                    // processor need to add it
                    XMLUtils.addOrSetAttribute(resAtts, ATTRIBUTE_NAMESPACE_PREFIX_DITAARCHVERSION, ditaarchNSValue);
                }

                writeStartElement(output, qName, resAtts);
            }

        } catch (final Exception e) {
            logger.logError(e.getMessage(), e);
        }
    }

    /**
     * Convenience method to write document start.
     */
    private void writeStartDocument(final Writer output) throws IOException {
        output.write(XML_HEAD);
    }

    /**
     * Convenience method to write an end element.
     * 
     * @param name element name
     */
    private void writeStartElement(final Writer output, final String name, final Attributes atts) throws IOException {
        output.write(LESS_THAN);
        output.write(name);
        for (int i = 0; i < atts.getLength(); i++) {
            writeAttribute(output, atts.getQName(i), atts.getValue(i));
        }
        output.write(GREATER_THAN);
    }

    /**
     * Convenience method to write an attribute.
     * 
     * @param name attribute name
     * @param value attribute value
     */
    private void writeAttribute(final Writer output, final String name, final String value) throws IOException {
        output.write(STRING_BLANK);
        output.write(name);
        output.write(EQUAL);
        output.write(QUOTATION);
        output.write(StringUtils.escapeXML(value));
        output.write(QUOTATION);
    }

    /**
     * Convenience method to write an end element.
     * 
     * @param name element name
     */
    private void writeEndElement(final Writer output, final String name) throws IOException {
        output.write(LESS_THAN);
        output.write(SLASH);
        output.write(name);
        output.write(GREATER_THAN);
    }

    /**
     * Convenience method to write a processing instruction.
     * 
     * @param name PI name
     * @param value PI value, may be {@code null}
     */
    private void writeProcessingInstruction(final Writer output, final String name, final String value)
            throws IOException {
        output.write(LESS_THAN);
        output.write(QUESTION);
        output.write(name);
        if (value != null) {
            output.write(STRING_BLANK);
            output.write(value);
        }
        output.write(QUESTION);
        output.write(GREATER_THAN);
    }

    @Override
    public void write(final File filename) throws DITAOTException {
        // pass map's directory path
        filePath = filename;
        // not chunk "by-topic"
        if (!separate) {
            // TODO create the initial output
            output = new StringWriter();
            processChunk(elem, null);
        } else {
            // chunk "by-topic"
            separateChunk(elem);
        }
        if (!copyto.isEmpty()) {
            updateList(); // now only update copyto list and does not update any
                          // topic ditamap ditamaptopic list
        }
    }

    private void updateList() {
        try {
            // XXX: This may have to use new
            // File(FileUtils.resolveFile(filePath,FILE_NAME_DITA_LIST_XML)).getParent()
            final Job job = new Job(filePath);
            final Map<File, File> copytotarget2sourcemaplist = job.getCopytoMap();
            copytotarget2source.putAll(copytotarget2sourcemaplist);
            for (final String file : copytoSource) {
                job.getOrCreateFileInfo(file).isCopyToSource = true;
            }
            job.setCopytoMap(copytotarget2source);
            job.write();
        } catch (final Exception e) {
            /* logger.logWarn(e.toString()); */
            logger.logError(e.getMessage(), e);
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (final IOException e) {
                    logger.logError(e.getMessage(), e);
                }
            }
        }
    }

    private void separateChunk(final Element element) {
        final String hrefValue = element.getAttribute(ATTRIBUTE_NAME_HREF);
        final String copytoValue = element.getAttribute(ATTRIBUTE_NAME_COPY_TO);
        final String scopeValue = element.getAttribute(ATTRIBUTE_NAME_SCOPE);
        String parseFilePath = null;
        Writer tempOutput = null;
        final String chunkValue = element.getAttribute(ATTRIBUTE_NAME_CHUNK);
        final String processRoleValue = element.getAttribute(ATTRIBUTE_NAME_PROCESSING_ROLE);
        boolean dotchunk = false;

        if (copytoValue.length() != 0 && !chunkValue.contains(ATTR_CHUNK_VALUE_TO_CONTENT)) {
            if (getFragment(hrefValue) != null) {
                parseFilePath = copytoValue + SHARP + getFragment(hrefValue);
            } else {
                parseFilePath = copytoValue;
            }
        } else {
            parseFilePath = hrefValue;
        }

        // if @copy-to is processed in chunk module, the list file needs to be
        // updated.
        // Because @copy-to should be included in fulltopiclist, and the source
        // of coyy-to should be excluded in fulltopiclist.
        if (copytoValue.length() != 0 && chunkValue.contains(ATTR_CHUNK_VALUE_TO_CONTENT)) {
            copyto.add(copytoValue);
            if (getFragment(hrefValue) != null) {
                copytoSource.add(stripFragment(hrefValue));
                copytotarget2source.put(toFile(copytoValue), toFile(stripFragment(hrefValue)));
            } else {
                copytoSource.add(hrefValue);
                copytotarget2source.put(toFile(copytoValue), toFile(hrefValue));
            }
        }
        try {
            if (!StringUtils.isEmptyString(parseFilePath) && !ATTR_SCOPE_VALUE_EXTERNAL.equalsIgnoreCase(scopeValue)
                    && !ATTR_PROCESSING_ROLE_VALUE_RESOURCE_ONLY.equalsIgnoreCase(processRoleValue)) {
                // if the path to target file make sense
                currentParsingFile = FileUtils.resolveFile(filePath, parseFilePath).getPath();
                String outputFileName = null;
                /*
                 * FIXME: we have code flaws here, references in ditamap need to
                 * be updated to new created file.
                 */
                String id = null;
                String firstTopicID = null;
                if (getFragment(parseFilePath) != null) {
                    id = getFragment(parseFilePath);
                    if (chunkValue.contains(ATTR_CHUNK_VALUE_SELECT_BRANCH)) {
                        outputFileName = FileUtils.resolveFile(filePath, id + FILE_EXTENSION_DITA).getPath();
                        targetTopicId = id;
                        startFromFirstTopic = false;
                        selectMethod = ATTR_CHUNK_VALUE_SELECT_BRANCH;
                    } else if (chunkValue.contains(ATTR_CHUNK_VALUE_SELECT_DOCUMENT)) {
                        firstTopicID = this.getFirstTopicId(FileUtils.resolveFile(filePath, parseFilePath).getPath());

                        topicDoc = DITAAttrUtils.getInstance().getTopicDoc(FileUtils.resolveFile(filePath, parseFilePath).getPath());

                        if (!StringUtils.isEmptyString(firstTopicID)) {
                            outputFileName = FileUtils.resolveFile(filePath, firstTopicID + FILE_EXTENSION_DITA).getPath();
                            targetTopicId = firstTopicID;
                        } else {
                            outputFileName = currentParsingFile + FILE_EXTENSION_CHUNK;
                            dotchunk = true;
                            targetTopicId = null;
                        }
                        selectMethod = ATTR_CHUNK_VALUE_SELECT_DOCUMENT;
                    } else {
                        outputFileName = FileUtils.resolveFile(filePath, id + FILE_EXTENSION_DITA).getPath();
                        targetTopicId = id;
                        startFromFirstTopic = false;
                        selectMethod = ATTR_CHUNK_VALUE_SELECT_TOPIC;
                    }
                } else {
                    firstTopicID = this.getFirstTopicId(FileUtils.resolveFile(filePath, parseFilePath).getPath());

                    topicDoc = DITAAttrUtils.getInstance().getTopicDoc(FileUtils.resolveFile(filePath, parseFilePath).getPath());

                    if (!StringUtils.isEmptyString(firstTopicID)) {
                        outputFileName = FileUtils.resolveFile(filePath, firstTopicID + FILE_EXTENSION_DITA).getPath();
                        targetTopicId = firstTopicID;
                    } else {
                        outputFileName = currentParsingFile + FILE_EXTENSION_CHUNK;
                        dotchunk = true;
                        targetTopicId = null;
                    }
                    selectMethod = ATTR_CHUNK_VALUE_SELECT_DOCUMENT;
                }
                if (copytoValue.length() != 0) {
                    // use @copy-to value as the new file name
                    outputFileName = FileUtils.resolveFile(filePath, copytoValue).getPath();
                }

                if (FileUtils.fileExists(outputFileName)) {
                    final String t = outputFileName;
                    outputFileName = FileUtils.resolveFile(filePath, generateFilename()).getPath();
                    conflictTable.put(outputFileName, t);
                    dotchunk = false;
                }
                tempOutput = output;
                output = new OutputStreamWriter(new FileOutputStream(outputFileName), UTF8);
                outputFile = outputFileName;
                if (!dotchunk) {
                    changeTable.put(FileUtils.resolveTopic(filePath, parseFilePath),
                                    outputFileName + (id == null ? "" : SHARP + id));
                    // new generated file
                    changeTable.put(outputFileName, outputFileName);
                }
                // change the href value
                if (StringUtils.isEmptyString(firstTopicID)) {
                    element.setAttribute(ATTRIBUTE_NAME_HREF,
                            FileUtils.getRelativeUnixPath(filePath + UNIX_SEPARATOR + FILE_NAME_STUB_DITAMAP, outputFileName) + (id == null ? "" : SHARP + id));
                } else {
                    element.setAttribute(ATTRIBUTE_NAME_HREF,
                            FileUtils.getRelativeUnixPath(filePath + UNIX_SEPARATOR + FILE_NAME_STUB_DITAMAP, outputFileName) + SHARP + firstTopicID);
                }
                include = false;
                // just a mark?
                stub = element.getOwnerDocument().createElement(ELEMENT_STUB);
                siblingStub = element.getOwnerDocument().createElement(ELEMENT_STUB);
                // <element>
                // <stub/>
                // ...
                // </element>
                // <siblingstub/>
                // ...
                // Place stub
                if (element.hasChildNodes()) {
                    final NodeList list = element.getElementsByTagName(MAP_TOPICMETA.localName);
                    if (list.getLength() > 0) {
                        final Node node = list.item(0);
                        final Node nextSibling = node.getNextSibling();
                        // no sibling so node is the last child
                        if (nextSibling == null) {
                            node.getParentNode().appendChild(stub);
                        } else {
                            // has sibling node
                            node.getParentNode().insertBefore(stub, nextSibling);
                        }
                    } else {
                        // no topicmeta tag.
                        element.insertBefore(stub, element.getFirstChild());
                    }

                    // element.insertBefore(stub,element.getFirstChild());
                } else {
                    element.appendChild(stub);
                }

                // Place siblingStub
                if (element.getNextSibling() != null) {
                    element.getParentNode().insertBefore(siblingStub, element.getNextSibling());
                } else {
                    element.getParentNode().appendChild(siblingStub);
                }

                reader.setErrorHandler(new DITAOTXMLErrorHandler(currentParsingFile, logger));
                reader.parse(new File(currentParsingFile).toURI().toString());
                output.flush();

                // remove stub and siblingStub
                stub.getParentNode().removeChild(stub);
                siblingStub.getParentNode().removeChild(siblingStub);

            }
        } catch (final Exception e) {
            logger.logError(e.getMessage(), e);
        } finally {
            try {
                if (output != null) {
                    output.close();
                    if (dotchunk && !new File(currentParsingFile).delete()) {
                        logger.logError(MessageUtils.getInstance()
                                .getMessage("DOTJ009E", currentParsingFile, outputFile).toString());
                    }
                    if (dotchunk && !new File(outputFile).renameTo(new File(currentParsingFile))) {
                        logger.logError(MessageUtils.getInstance()
                                .getMessage("DOTJ009E", currentParsingFile, outputFile).toString());
                    }
                }
                output = tempOutput;
            } catch (final Exception ex) {
                logger.logError(ex.getMessage(), ex);
            }

        }

    }

    /**
     * Generate file name.
     * 
     * @return generated file name
     */
    private String generateFilename() {
        return chunkFilenameGenerator.generateFilename("Chunk", FILE_EXTENSION_DITA);
    }

    private void processChunk(final Element element, final String outputFile) {
        final String hrefValue = element.getAttribute(ATTRIBUTE_NAME_HREF);
        final String chunkValue = element.getAttribute(ATTRIBUTE_NAME_CHUNK);
        final String copytoValue = element.getAttribute(ATTRIBUTE_NAME_COPY_TO);
        final String scopeValue = element.getAttribute(ATTRIBUTE_NAME_SCOPE);
        final String classValue = element.getAttribute(ATTRIBUTE_NAME_CLASS);
        final String processRoleValue = element.getAttribute(ATTRIBUTE_NAME_PROCESSING_ROLE);
        // Get id value
        final String id = element.getAttribute(ATTRIBUTE_NAME_ID);
        // Get navtitle value
        final String navtitle = element.getAttribute(ATTRIBUTE_NAME_NAVTITLE);

        // file which will be parsed
        String parseFilePath = null;
        String outputFileName = outputFile;
        // Writer tempWriter = null;
        Writer tempWriter = new StringWriter();
        // Set<String> tempTopicID = null;
        Set<String> tempTopicID = new HashSet<String>();

        targetTopicId = null;
        selectMethod = ATTR_CHUNK_VALUE_SELECT_DOCUMENT;
        include = false;

        boolean needWriteDitaTag = true;

        try {
            // Get target chunk file name
            if (copytoValue.length() != 0 && !chunkValue.contains(ATTR_CHUNK_VALUE_TO_CONTENT)) {
                if (getFragment(hrefValue) != null) {
                    parseFilePath = copytoValue + SHARP + getFragment(hrefValue);
                } else {
                    parseFilePath = copytoValue;
                }
            } else {
                parseFilePath = hrefValue;
            }

            // if @copy-to is processed in chunk module, the list file needs to
            // be updated.
            // Because @copy-to should be included in fulltopiclist, and the
            // source of coyy-to should be excluded in fulltopiclist.
            if (copytoValue.length() != 0 && chunkValue.contains(ATTR_CHUNK_VALUE_TO_CONTENT)
                    && hrefValue.length() != 0) {
                copyto.add(copytoValue);
                if (getFragment(hrefValue) != null) {
                    copytoSource.add(stripFragment(hrefValue));
                    copytotarget2source.put(toFile(copytoValue), toFile(stripFragment(hrefValue)));
                } else {
                    copytoSource.add(hrefValue);
                    copytotarget2source.put(toFile(copytoValue), toFile(hrefValue));
                }
            }

            if (!StringUtils.isEmptyString(classValue)) {
                if ((!MAPGROUP_D_TOPICGROUP.matches(classValue)) && (!StringUtils.isEmptyString(parseFilePath))
                        && (!ATTR_SCOPE_VALUE_EXTERNAL.equalsIgnoreCase(scopeValue))) {
                    // now the path to target file make sense
                    if (chunkValue.indexOf(ATTR_CHUNK_VALUE_TO_CONTENT) != -1) {
                        // if current element contains "to-content" in chunk
                        // attribute
                        // we need to create new buffer and flush the buffer to
                        // file
                        // after processing is finished
                        tempWriter = output;
                        tempTopicID = topicID;
                        output = new StringWriter();
                        topicID = new HashSet<String>();
                        // if
                        // (ELEMENT_NAME_MAP.equalsIgnoreCase(element.getNodeName()))
                        // {
                        if (MAP_MAP.matches(classValue)) {
                            // Very special case, we have a map element with
                            // href value.
                            // This is a map that needs to be chunked to
                            // content.
                            // No need to parse any file, just generate a stub
                            // output.
                            outputFileName = FileUtils.resolveFile(filePath, parseFilePath).getPath();
                            needWriteDitaTag = false;
                        } else if (copytoValue.length() != 0) {
                            // use @copy-to value as the new file name
                            outputFileName = FileUtils.resolveFile(filePath, copytoValue).getPath();
                        } else if (hrefValue.length() != 0) {
                            // try to use href value as the new file name
                            if (chunkValue.contains(ATTR_CHUNK_VALUE_SELECT_TOPIC)
                                    || chunkValue.contains(ATTR_CHUNK_VALUE_SELECT_BRANCH)) {
                                if (getFragment(hrefValue) != null) {
                                    // if we have an ID here, use it.
                                    outputFileName = FileUtils.resolveFile(filePath,
                                            getFragment(hrefValue) + FILE_EXTENSION_DITA).getPath();
                                } else {
                                    // Find the first topic id in target file if
                                    // any.
                                    final String firstTopic = this.getFirstTopicId(FileUtils.resolveFile(filePath,
                                            hrefValue).getPath());
                                    if (!StringUtils.isEmptyString(firstTopic)) {
                                        outputFileName = FileUtils.resolveFile(filePath,
                                                firstTopic + FILE_EXTENSION_DITA).getPath();
                                    } else {
                                        outputFileName = FileUtils.resolveFile(filePath, hrefValue).getPath();
                                    }
                                }
                            } else {
                                // otherwise, use the href value instead
                                outputFileName = FileUtils.resolveFile(filePath, hrefValue).getPath();
                            }
                        } else {
                            // use randomly generated file name
                            outputFileName = FileUtils.resolveFile(filePath, generateFilename()).getPath();
                        }

                        // Check if there is any conflict
                        if (FileUtils.fileExists(outputFileName) && !MAP_MAP.matches(classValue)) {
                            final String t = outputFileName;
                            outputFileName = FileUtils.resolveFile(filePath, generateFilename()).getPath();
                            conflictTable.put(outputFileName, t);
                        }
                        // add newly generated file to changTable
                        // the new entry in changeTable has same key and value
                        // in order to indicate it is a newly generated file
                        changeTable.put(outputFileName, outputFileName);
                    }
                    // "by-topic" couldn't reach here
                    this.outputFile = outputFileName;

                    {
                        final String path = FileUtils.resolveTopic(filePath, parseFilePath);
                        String newpath = null;
                        if (getFragment(path) != null) {
                            newpath = outputFileName + SHARP + getFragment(path);
                        } else {
                            final String firstTopicID = this.getFirstTopicId(path);
                            if (!StringUtils.isEmptyString(firstTopicID)) {
                                newpath = outputFileName + SHARP + firstTopicID;
                            } else {
                                newpath = outputFileName;
                            }
                        }
                        // add file name changes to changeTable, this will be
                        // used in
                        // TopicRefWriter's updateHref method, very important!!!
                        changeTable.put(path, newpath);
                        // update current element's @href value
                        element.setAttribute(ATTRIBUTE_NAME_HREF, FileUtils.getRelativeUnixPath(filePath
                                + UNIX_SEPARATOR + FILE_NAME_STUB_DITAMAP, newpath));
                    }

                    if (getFragment(parseFilePath) != null) {
                        targetTopicId = getFragment(parseFilePath);
                    }

                    if (chunkValue.indexOf("select") != -1) {
                        final int endIndex = chunkValue.indexOf(STRING_BLANK, chunkValue.indexOf("select"));
                        if (endIndex == -1) {
                            // if there is no space after select-XXXX in chunk
                            // attribute
                            selectMethod = chunkValue.substring(chunkValue.indexOf("select"));
                        } else {
                            selectMethod = chunkValue.substring(chunkValue.indexOf("select"), endIndex);
                        }

                        if (ATTR_CHUNK_VALUE_SELECT_TOPIC.equals(selectMethod)
                                || ATTR_CHUNK_VALUE_SELECT_BRANCH.equals(selectMethod)) {
                            // if the current topic href referred to a entire
                            // topic file,it will be handled in "document"
                            // level.
                            if (targetTopicId == null) {
                                selectMethod = ATTR_CHUNK_VALUE_SELECT_DOCUMENT;
                            }
                        }
                    }
                    final String tempPath = currentParsingFile;
                    currentParsingFile = FileUtils.resolveFile(filePath, parseFilePath).getPath();

                    if (!ATTR_PROCESSING_ROLE_VALUE_RESOURCE_ONLY.equalsIgnoreCase(processRoleValue)) {
                        currentParsingFileTopicIDChangeTable = new HashMap<String, String>();
                        // TODO recursive point
                        reader.parse(new File(currentParsingFile).toURI().toString());
                        if (currentParsingFileTopicIDChangeTable.size() > 0) {
                            String href = element.getAttribute(ATTRIBUTE_NAME_HREF);
                            href = FileUtils.separatorsToUnix(href);
                            final String pathtoElem = getFragment(href) != null ? getFragment(href) : "";

                            final String old_elementid = pathtoElem.contains(SLASH) ? pathtoElem.substring(0,
                                    pathtoElem.indexOf(SLASH)) : pathtoElem;

                            if (old_elementid.length() > 0) {
                                final String new_elementid = currentParsingFileTopicIDChangeTable.get(old_elementid);
                                if (new_elementid != null && new_elementid.length() > 0) {
                                    href = href.replaceFirst(SHARP + old_elementid, SHARP + new_elementid);
                                    element.setAttribute(ATTRIBUTE_NAME_HREF, href);
                                }

                            }
                        }
                        currentParsingFileTopicIDChangeTable = null;
                    }
                    // restore the currentParsingFile
                    currentParsingFile = tempPath;
                }

                // use @copy-to value(dita spec v1.2)
                if (outputFileName == null) {
                    if (!StringUtils.isEmptyString(copytoValue)) {
                        outputFileName = FileUtils.resolveFile(filePath, copytoValue).getPath();
                        // use id value
                    } else if (!StringUtils.isEmptyString(id)) {
                        outputFileName = FileUtils.resolveFile(filePath, id + FILE_EXTENSION_DITA).getPath();
                    } else {
                        // use randomly generated file name
                        outputFileName = FileUtils.resolveFile(filePath, generateFilename()).getPath();
                        // Check if there is any conflict
                        if (FileUtils.fileExists(outputFileName) && !MAP_MAP.matches(classValue)) {
                            final String t = outputFileName;
                            outputFileName = FileUtils.resolveFile(filePath, generateFilename()).getPath();
                            conflictTable.put(outputFileName, t);
                        }
                    }

                    // if topicref has child node or topicref has @navtitle
                    if (element.hasChildNodes() || !StringUtils.isEmptyString(navtitle)) {

                        final DITAAttrUtils utils = DITAAttrUtils.getInstance();

                        String navtitleValue = null;
                        String shortDescValue = null;
                        // get navtitle value.
                        navtitleValue = utils.getChildElementValueOfTopicmeta(element, TOPIC_NAVTITLE.matcher);
                        // get shortdesc value
                        shortDescValue = utils.getChildElementValueOfTopicmeta(element, MAP_SHORTDESC.matcher);
                        // no navtitle tag exists.
                        if (navtitleValue == null) {
                            // use @navtitle
                            navtitleValue = navtitle;
                        }

                        // add newly generated file to changTable
                        // the new entry in changeTable has same key and value
                        // in order to indicate it is a newly generated file
                        changeTable.put(outputFileName, outputFileName);
                        // update current element's @href value
                        // create a title-only topic when there is a title
                        if (!StringUtils.isEmptyString(navtitleValue)) {
                            element.setAttribute(ATTRIBUTE_NAME_HREF, FileUtils.getRelativeUnixPath(filePath
                                    + UNIX_SEPARATOR + FILE_NAME_STUB_DITAMAP, outputFileName));
                            // manually create a new topic chunk
                            final StringBuffer buffer = new StringBuffer();
                            buffer.append("<topic id=\"topic\" class=\"- topic/topic \">")
                                    .append("<title class=\"- topic/title \">").append(navtitleValue)
                                    .append("</title>");
                            // has shortdesc value
                            if (shortDescValue != null) {
                                buffer.append("<shortdesc class=\"- topic/shortdesc \">").append(shortDescValue)
                                        .append("</shortdesc>");
                            }
                            buffer.append("</topic>");

                            final StringReader rder = new StringReader(buffer.toString());
                            final InputSource source = new InputSource(rder);

                            // for recursive
                            final String tempPath = currentParsingFile;
                            currentParsingFile = outputFileName;
                            // insert not append the nested topic
                            parseFilePath = outputFileName;
                            // create chunk
                            reader.parse(source);
                            // restore the currentParsingFile
                            currentParsingFile = tempPath;
                        }

                    }

                }
                // Added 20100818 for bug:3042978 end

                if (element.hasChildNodes()) {
                    // if current element has child nodes and chunk results for
                    // this element has value
                    // which means current element makes sense for chunk action.
                    final StringWriter temp = (StringWriter) output;
                    output = new StringWriter();
                    final NodeList children = element.getChildNodes();
                    for (int i = 0; i < children.getLength(); i++) {
                        final Node current = children.item(i);
                        if (current.getNodeType() == Node.ELEMENT_NODE
                                && ((Element) current).getAttribute(ATTRIBUTE_NAME_CLASS).indexOf(MAP_TOPICREF.matcher) != -1) {
                            processChunk((Element) current, outputFileName);
                        }
                    }

                    // merge results
                    final StringBuffer parentResult = temp.getBuffer();
                    // Skip empty parents and @processing-role='resource-only'
                    // entries.
                    if (parentResult.length() > 0 && !StringUtils.isEmptyString(parseFilePath)
                            && !ATTR_PROCESSING_ROLE_VALUE_RESOURCE_ONLY.equalsIgnoreCase(processRoleValue)) {
                        int insertpoint = parentResult.lastIndexOf("</");
                        final int end = parentResult.indexOf(">", insertpoint);

                        if (insertpoint == -1 || end == -1) {
                            logger.logError(MessageUtils.getInstance().getMessage("DOTJ033E", hrefValue).toString());
                        } else {
                            if (ELEMENT_NAME_DITA.equalsIgnoreCase(parentResult.substring(insertpoint, end).trim())) {
                                insertpoint = parentResult.lastIndexOf("</", insertpoint);
                            }
                            parentResult.insert(insertpoint, ((StringWriter) output).getBuffer());
                        }
                    } else {
                        parentResult.append(((StringWriter) output).getBuffer());
                    }
                    // restore back to parent's output this is a different temp
                    output = temp;

                }

                if (chunkValue.indexOf(ATTR_CHUNK_VALUE_TO_CONTENT) != -1) {
                    // flush the buffer to file after processing is finished
                    // and restore back original output

                    final FileOutputStream fileOutput = new FileOutputStream(outputFileName);
                    OutputStreamWriter ditaFileOutput = null;
                    try {
                        ditaFileOutput = new OutputStreamWriter(fileOutput, UTF8);
                        if (outputFileName.equals(changeTable.get(outputFileName))) {
                            // if the output file is newly generated file
                            // write the xml header and workdir PI into new file
                            writeStartDocument(ditaFileOutput);
                            final File workDir = new File(outputFileName).getParentFile().getAbsoluteFile();
                            if (OS_NAME.toLowerCase().indexOf(OS_NAME_WINDOWS) == -1) {
                                writeProcessingInstruction(ditaFileOutput, PI_WORKDIR_TARGET, workDir.getAbsolutePath());
                            } else {
                                writeProcessingInstruction(ditaFileOutput, PI_WORKDIR_TARGET,
                                        UNIX_SEPARATOR + workDir.getAbsolutePath());
                            }
                            writeProcessingInstruction(ditaFileOutput, PI_WORKDIR_TARGET_URI, workDir.toURI()
                                    .toString());

                            if ((conflictTable.get(outputFileName) != null)) {
                                final String relativePath = FileUtils.getRelativeUnixPath(filePath + UNIX_SEPARATOR
                                        + FILE_NAME_STUB_DITAMAP, conflictTable.get(outputFileName));
                                String path2project = FileUtils.getRelativeUnixPath(relativePath);
                                if (null == path2project) {
                                    path2project = "";
                                }

                                writeProcessingInstruction(ditaFileOutput, PI_PATH2PROJ_TARGET, path2project);
                            }
                        }
                        if (needWriteDitaTag) {
                            final AttributesImpl atts = new AttributesImpl();
                            XMLUtils.addOrSetAttribute(atts, ATTRIBUTE_NAMESPACE_PREFIX_DITAARCHVERSION,
                                    ditaarchNSValue);
                            XMLUtils.addOrSetAttribute(atts, ATTRIBUTE_PREFIX_DITAARCHVERSION + COLON
                                    + ATTRIBUTE_NAME_DITAARCHVERSION, "1.2");
                            writeStartElement(ditaFileOutput, ELEMENT_NAME_DITA, atts);
                        }
                        // write the final result to the output file
                        ditaFileOutput.write(((StringWriter) output).getBuffer().toString());
                        if (needWriteDitaTag) {
                            writeEndElement(ditaFileOutput, ELEMENT_NAME_DITA);
                        }
                        ditaFileOutput.flush();
                    } finally {
                        ditaFileOutput.close();
                    }
                    // restore back original output
                    output = tempWriter;
                    topicID = tempTopicID;
                }
            }
        } catch (final Exception e) {
            logger.logError(e.getMessage(), e);
        }

    }

    /**
     * Set up the class.
     * 
     * @param changeTable changeTable
     * @param conflictTable conflictTable
     * @param refFileSet refFileSet
     * @param elem chunking topicref
     * @param separate separate
     * @param chunkByTopic chunkByTopic
     */
    public void setup(final LinkedHashMap<String, String> changeTable, final Hashtable<String, String> conflictTable,
            final Set<String> refFileSet, final Element elem, final boolean separate, final boolean chunkByTopic,
            final ChunkFilenameGenerator chunkFilenameGenerator) {
        // Initialize ChunkTopicParser
        this.changeTable = changeTable;
        this.elem = elem;
        this.separate = separate;
        this.conflictTable = conflictTable;
        this.chunkFilenameGenerator = chunkFilenameGenerator;
    }

    /** Check whether current href needs to be updated */
    private boolean checkHREF(final Attributes atts) {
        final String hrefValue = atts.getValue(ATTRIBUTE_NAME_HREF);
        if (hrefValue == null || hrefValue.indexOf(COLON_DOUBLE_SLASH) != -1) {
            return false;
        }
        String scopeValue = atts.getValue(ATTRIBUTE_NAME_SCOPE);
        if (scopeValue == null) {
            scopeValue = ATTR_SCOPE_VALUE_LOCAL;
        }
        if (scopeValue != null && scopeValue.equals(ATTR_SCOPE_VALUE_EXTERNAL)) {
            return false;
        }

        return true;
    }

    /**
     * 
     * Get the first topic id from the given dita file.
     * 
     * @param absolutePathToFile The absolute path to a dita file.
     * @return The first topic id from the given dita file if success, otherwise
     *         an empty string is returned.
     */
    private String getFirstTopicId(final String absolutePathToFile) {
        final StringBuffer firstTopicId = new StringBuffer();

        if (absolutePathToFile == null || !FileUtils.isAbsolutePath(absolutePathToFile)) {
            return firstTopicId.toString();
        }

        final TopicIdParser parser = new TopicIdParser(firstTopicId);
        try {
            final XMLReader reader = StringUtils.getXMLReader();
            reader.setContentHandler(parser);
            reader.parse(new File(absolutePathToFile).toURI().toString());
        } catch (final Exception e) {
            logger.logError(e.getMessage(), e);
        }
        return firstTopicId.toString();

    }

    /**
     * Create topicmeta node.
     * 
     * @param element document element of a topic file.
     * @param newChild node to be appended by topicmeta.
     */
    private void createTopicMeta(final Element element, final Element newChild) {

        // create topicmeta element
        final Element topicmeta = elem.getOwnerDocument().createElement(MAP_TOPICMETA.localName);
        topicmeta.setAttribute(ATTRIBUTE_NAME_CLASS, MAP_TOPICMETA.toString());
        newChild.appendChild(topicmeta);

        final DITAAttrUtils utils = DITAAttrUtils.getInstance();

        // iterate the node.
        if (element != null) {
            // search for title and navtitle tag
            final NodeList list = element.getChildNodes();
            Node title = null;
            Node navtitle = null;
            for (int i = 0; i < list.getLength(); i++) {
                final Node node = list.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    final Element childNode = (Element) node;

                    if (childNode.getAttribute(ATTRIBUTE_NAME_CLASS).contains(TOPIC_TITLE.matcher)) {
                        // set title node
                        title = childNode;
                    }
                    // navtitle node
                    if (childNode.getAttribute(ATTRIBUTE_NAME_CLASS).contains(TOPIC_TITLEALTS.matcher)) {
                        final NodeList subList = childNode.getChildNodes();
                        for (int j = 0; j < subList.getLength(); j++) {
                            final Node subNode = subList.item(j);
                            if (subNode.getNodeType() == Node.ELEMENT_NODE) {
                                final Element subChildNode = (Element) subNode;
                                if (subChildNode.getAttribute(ATTRIBUTE_NAME_CLASS).contains(TOPIC_NAVTITLE.matcher)) {
                                    // set navtitle node
                                    navtitle = subChildNode;
                                }
                            }
                        }
                    }
                }
            }
            // shordesc node
            Node shortDesc = null;
            final NodeList nodeList = element.getChildNodes();
            for (int i = 0; i < nodeList.getLength(); i++) {
                final Node node = nodeList.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    final Element elem = (Element) node;
                    final String clazzValue = elem.getAttribute(ATTRIBUTE_NAME_CLASS);
                    // if needed node is found
                    if (clazzValue != null && TOPIC_SHORTDESC.matches(clazzValue)) {
                        shortDesc = elem;
                    }
                }
            }

            final Element navtitleNode = elem.getOwnerDocument().createElement(TOPIC_NAVTITLE.localName);
            navtitleNode.setAttribute(ATTRIBUTE_NAME_CLASS, TOPIC_NAVTITLE.toString());
            // append navtitle node
            if (navtitle != null) {
                // Get text value
                final String text = utils.getText(navtitle);
                final Text titleText = elem.getOwnerDocument().createTextNode(text);
                navtitleNode.appendChild(titleText);
                topicmeta.appendChild(navtitleNode);

            } else {
                // Get text value
                final String text = utils.getText(title);
                final Text titleText = elem.getOwnerDocument().createTextNode(text);
                navtitleNode.appendChild(titleText);
                topicmeta.appendChild(navtitleNode);
            }

            // append gentext pi
            final Node pi = elem.getOwnerDocument().createProcessingInstruction("ditaot", "gentext");
            topicmeta.appendChild(pi);

            // append linktext
            final Element linkTextNode = elem.getOwnerDocument().createElement(TOPIC_LINKTEXT.localName);
            linkTextNode.setAttribute(ATTRIBUTE_NAME_CLASS, MAP_LINKTEXT.toString());
            // Get text value
            final String text = utils.getText(title);
            final Text textNode = elem.getOwnerDocument().createTextNode(text);
            linkTextNode.appendChild(textNode);
            topicmeta.appendChild(linkTextNode);

            // append genshortdesc pi
            final Node pii = elem.getOwnerDocument().createProcessingInstruction("ditaot", "genshortdesc");
            topicmeta.appendChild(pii);

            // append shortdesc
            final Element shortDescNode = elem.getOwnerDocument().createElement(TOPIC_SHORTDESC.localName);
            shortDescNode.setAttribute(ATTRIBUTE_NAME_CLASS, MAP_SHORTDESC.toString());
            // Get text value
            final String shortDescText = utils.getText(shortDesc);
            final Text shortDescTextNode = elem.getOwnerDocument().createTextNode(shortDescText);
            shortDescNode.appendChild(shortDescTextNode);
            topicmeta.appendChild(shortDescNode);

        }
    }

}
