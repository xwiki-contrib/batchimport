/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.batchimport.internal;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.batchimport.BatchImport;
import org.xwiki.batchimport.BatchImportConfiguration;
import org.xwiki.batchimport.RowDataPostprocessor;
import org.xwiki.batchimport.BatchImportConfiguration.Overwrite;
import org.xwiki.batchimport.ImportFileIterator;
import org.xwiki.batchimport.MappingPreviewResult;
import org.xwiki.batchimport.internal.log.StringBatchImportLog;
import org.xwiki.batchimport.log.AbstractSavedDocumentsBatchImportLog;
import org.xwiki.batchimport.log.BatchImportLog;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContext;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.officeimporter.document.XDOMOfficeDocument;
import org.xwiki.officeimporter.script.OfficeImporterScriptService;
import org.xwiki.script.service.ScriptService;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.api.Document;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.PropertyInterface;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.BooleanClass;
import com.xpn.xwiki.objects.classes.DateClass;
import com.xpn.xwiki.objects.classes.ListClass;
import com.xpn.xwiki.objects.classes.NumberClass;
import com.xpn.xwiki.objects.classes.StringClass;
import com.xpn.xwiki.objects.classes.TextAreaClass;
import com.xpn.xwiki.web.Utils;

/**
 * Default Batch import implementation, uses {@link ImportFileIterator}s to process the files to import, according to
 * the {@link BatchImportConfiguration#getType()} setting. If you need to import from a new format, register a new
 * {@link ImportFileIterator} implementation with a hint which you then pass in the {@link BatchImportConfiguration}.
 *
 * @version $Id$
 */
@Component
public class DefaultBatchImport implements BatchImport
{
    @Inject
    protected Execution execution;

    protected static final Logger LOGGER = LoggerFactory.getLogger(DefaultBatchImport.class);

    @Inject
    protected ComponentManager cm;

    protected boolean debug = true;

    protected boolean log = true;

    @Inject
    @Named("current/reference")
    protected DocumentReferenceResolver<EntityReference> currentDocumentEntityReferenceResolver;

    @Inject
    @Named("current")
    protected DocumentReferenceResolver<String> currentDocumentStringResolver;

    @Inject
    @Named("current")
    protected EntityReferenceResolver<String> currentStringResolver;

    @Inject
    protected EntityReferenceSerializer<String> entityReferenceSerializer;

    /**
     * We need this to prepare fullNames from references
     */
    @SuppressWarnings("unchecked")
    private EntityReferenceSerializer<String> localEntityReferenceSerializer = Utils.getComponent(
        EntityReferenceSerializer.class, "local");

    /**
     * {@inheritDoc}
     *
     * @see org.xwiki.batchimport.BatchImport#getColumnHeaders(org.xwiki.batchimport.BatchImportConfiguration)
     */
    @Override
    public List<String> getColumnHeaders(BatchImportConfiguration config) throws IOException
    {
        // get an iterator from the config and reset its file
        ImportFileIterator iterator = null;
        try {
            iterator = getImportFileIterator(config);
        } catch (Exception e) {
            throw new IOException("Could not find an import file reader for the configuration: " + config.toString(), e);
        }
        iterator.resetFile(config);

        // and pass it to the internal function
        return getColumnHeaders(iterator, config.hasHeaderRow());
    }

    /**
     * @param iterator the file iterator to read header from
     * @param hasHeaderRow whether the file has header row or not, to know how to return the values for the first row
     * @return the column headers of the file to process, that is the values from the first line from the file. If has
     *         header row is true, values will be returned as they are, otherwise they will be processed by adding the
     *         "(<column number>)' string at the end of the value. The returned list preserves order so the actual index
     *         of the column should be taken from the index in this list.
     * @throws IOException if the file cannot be read
     */
    protected List<String> getColumnHeaders(ImportFileIterator iterator, boolean hasHeaderRow) throws IOException
    {
        List<String> columns;

        List<String> headerLine = iterator.readNextLine();
        if (headerLine == null) {
            return null;
        }

        columns = new ArrayList<String>();
        for (int i = 0; i < headerLine.size(); i++) {
            String header = headerLine.get(i);
            // handle this line differently depending on whether this file has header or not
            if (hasHeaderRow) {
                columns.add(header);
            } else {
                // print column number first and then value in brackets. The returned list preserves order so the
                // actual index of the column should be taken from the index in the list
                columns.add(String.format("%d (%s)", i, header));
            }
        }

        return columns;
    }

    @Override
    public MappingPreviewResult getMappingPreview(BatchImportConfiguration config, int maxRows) throws IOException,
        XWikiException
    {
        return this.getMappingPreview(config, maxRows, null);
    }

    @Override
    public MappingPreviewResult getMappingPreview(BatchImportConfiguration config, int maxRows, String logHint)
        throws IOException, XWikiException
    {
        XWikiContext xcontext = getXWikiContext();
        XWiki xwiki = xcontext.getWiki();

        // log to report how this import goes
        BatchImportLog log = getLog(logHint);

        // the file to import
        ImportFileIterator metadatafilename = null;
        try {
            metadatafilename = getImportFileIterator(config);
        } catch (ComponentLookupException e) {
            // IOException directly from the getFileIterator method
            throw new IOException("Could not find an import file reader for the configuration: " + config.toString(), e);
        }
        // mapping from the class fields to source file columns
        Map<String, String> mapping = config.getFieldsMapping();

        // class to map data to (objects of this class will be created)
        BaseClass defaultClass =
            xwiki.getXClass(
                currentDocumentStringResolver.resolve(config.getMappingClassName(),
                    StringUtils.isEmpty(config.getWiki()) ? null : new WikiReference(config.getWiki())), xcontext);
        // TODO: validate that mapping is correct on top of this class, issue an "Error" if not (more of a warning than
        // an error): check that fields exist, etc
        // the locale of the data to read
        Locale sourceLocale = config.getLocale();
        // prepare the default date formatter to process the dates in this row
        DateFormat defaultDateFormatter = null;
        String defaultDateFormat = config.getDefaultDateFormat();
        if (StringUtils.isEmpty(defaultDateFormat)) {
            if (sourceLocale != null) {
                defaultDateFormatter = DateFormat.getDateInstance(DateFormat.MEDIUM, sourceLocale);
            }
            // get it from preferences, hoping that it's set
            defaultDateFormat = xcontext.getWiki().getXWikiPreference("dateformat", xcontext);
        }
        defaultDateFormatter = new SimpleDateFormat(defaultDateFormat);

        // and get a number formatter, we'll use this to process the numbers a bit.
        NumberFormat numberFormatter = null;
        if (sourceLocale != null) {
            numberFormatter = NumberFormat.getInstance(sourceLocale);
        }

        // whether this file has header row or not (whether first line needs to be imported or not)
        boolean hasHeaderRow = config.hasHeaderRow();

        // list of document names, used to remember what are the document names that were generated from the
        // document. Note that for multiple imports from the same document, this list should be identical.
        List<DocumentReference> docNameList = new ArrayList<DocumentReference>();

        // prepare the list of data postprocessors that are available
        List<RowDataPostprocessor> dataProcessors;
        try {
            dataProcessors = this.getRowDataPostprocessors();
        } catch (ComponentLookupException e) {
            throw new XWikiException(0, 0, "Could not fetch data postprocessors for the batch import", e);
        }

        // the previewed rows: processed and validated
        List<Map<String, Object>> previewRows = new LinkedList<Map<String, Object>>();

        // start reading the rows and process them one by one
        metadatafilename.resetFile(config);
        List<String> currentLine = null;
        int rowIndex = 0;
        List<String> headers = null;
        // if there is no header row the headers are the numbers of the columns as strings
        if (hasHeaderRow) {
            headers = getColumnHeaders(metadatafilename, hasHeaderRow);
            currentLine = metadatafilename.readNextLine();
            rowIndex = 1;
        } else {
            currentLine = metadatafilename.readNextLine();
            headers = new ArrayList<String>();
            for (int i = 0; i < currentLine.size(); i++) {
                headers.add(Integer.toString(i));
            }
        }

        while (currentLine != null) {
            // break if we already did the rows
            if (rowIndex > maxRows) {
                break;
            }

            Map<String, String> data = getData(currentLine, mapping, headers);

            // postprocess the data with visitors (all registered visitors). The order of the visitors is the one of the
            // priorities
            for (RowDataPostprocessor proc : dataProcessors) {
                proc.postProcessRow(data, currentLine, rowIndex, mapping, headers, config);
            }

            // generate page name
            DocumentReference generatedDocName = getPageName(data, rowIndex, config, docNameList);
            boolean pageNameValid = true;
            // process the row
            if (generatedDocName != null) {
                // check if it's duplicated name
                boolean isDuplicateName = docNameList.contains(generatedDocName);
                if (!isDuplicateName) {
                    docNameList.add(generatedDocName);
                }
                // validate the page name (check if it fits in the xwiki db, length of fullName, etc)
                pageNameValid = validatePageName(generatedDocName, rowIndex, currentLine, mapping, data, true, log);
            }
            Map<String, Object> parsedData =
                parseAndValidatePageData(data, rowIndex, currentLine,
                    this.entityReferenceSerializer.serialize(generatedDocName), defaultClass, config,
                    defaultDateFormatter, numberFormatter, true, log, true);
            // add the generated document reference in the parsed data
            parsedData
                .put("doc.reference", pageNameValid ? ((generatedDocName == null) ? "" : generatedDocName) : null);

            previewRows.add(parsedData);

            // go to next line
            currentLine = metadatafilename.readNextLine();
            rowIndex++;
        }

        log.log("donepreview");

        return new MappingPreviewResult(previewRows, log);
    }

    protected List<String> getAsList(String fields, Character separator)
    {
        List<String> list = new ArrayList<String>();
        if (fields == null)
            return list;
        for (String item : fields.split(Pattern.quote(separator.toString()))) {
            if (!StringUtils.isEmpty(item.trim())) {
                list.add(item.trim());
            }
        }
        return list;
    }

    public void debug(String message)
    {
        if (this.debug) {
            // yeah, debug with info here because "debug" are billions of billions and we cannot really understand
            // anything from it
            LOGGER.info(message);
        }
    }

    public Map<String, String> getDataOriginal(List<String> row, Map<String, List<String>> reverseMapping,
        List<String> headers)
    {
        // this map seems to be like xwiki field -> value
        Map<String, String> map = new HashMap<String, String>();

        // TODO: in the original code, a test was made here for the first cell in the row, to check if it has content,
        // why?
        for (int i = 0; i < row.size(); i++) {
            String currentHeader = headers.get(i);
            List<String> xwikiFields = reverseMapping.get(currentHeader);
            if (xwikiFields != null) {
                for (String xwikiField : xwikiFields) {
                    map.put(xwikiField, row.get(i));
                }
            }
        }
        return map;
    }

    public Map<String, String> getData(List<String> row, Map<String, String> mapping, List<String> headers)
    {
        // this map seems to be like xwiki field -> value
        Map<String, String> map = new HashMap<String, String>();
        // TODO: in the original code, a test was made here for the first cell in the row, to check if it has content,
        // why?

        // get the fields in the mapping
        for (Map.Entry<String, String> fieldMapping : mapping.entrySet()) {
            String xwikiField = fieldMapping.getKey();
            String header = fieldMapping.getValue();
            int valueIndex = headers.indexOf(header);
            if (valueIndex >= 0 && valueIndex < row.size()) {
                map.put(xwikiField, row.get(valueIndex));
            }
        }

        return map;
    }

    public String getSpace(Map<String, String> data, String defaultSpace, boolean clearNames)
    {
        String space = data.get("doc.space");
        if (StringUtils.isEmpty(space)) {
            space = defaultSpace;
        } else {
            // clear the name of the space only if it comes from the data, not if it comes from config. If it comes from
            // config it will have to be cleaned by the config setter
            if (clearNames) {
                XWikiContext xcontext = getXWikiContext();
                space = xcontext.getWiki().clearName(space, xcontext);
            }
        }
        return space;
    }

    public DocumentReference getPageName(Map<String, String> data, int rowIndex, BatchImportConfiguration config,
        List<DocumentReference> docNameList)
    {
        // pagename prefix used to automatically generate page names, when _name is not provided
        String defaultPrefix = config.getEmptyDocNamePrefix();
        // the default space to add pages in
        String defaultSpace = config.getDefaultSpace();
        // whether values in column doc.name should be passed through clearName before
        boolean clearNames = config.getClearName();
        // the wiki to add pages in
        String wiki = config.getWiki();

        // TODO: in the original code the space code was copy-pasted here, not used from the function
        String space = getSpace(data, defaultSpace, clearNames);

        String name = data.get("doc.name");
        if (StringUtils.isEmpty(name)) {
            // if the document name is empty in the file...
            if (StringUtils.isEmpty(defaultPrefix)) {
                // ... if there is no prefix to generate a document name, the name remains empty, figure out what to do
                if (StringUtils.isEmpty(data.get("doc.space"))) {
                    // if there is no explicit space mapped either for this line, ignore the line, there is no data on
                    // the line to make a reference from it (probably an empty line at the end of an excel?)
                    return null;
                } else {
                    // if there is an explicit space, most probably the intention is to create a non-terminal page, use
                    // the WebHome name for the document
                    XWikiContext xcontext = getXWikiContext();
                    name = xcontext.getWiki().getXWikiPreference("xwiki.defaultpage", "WebHome", xcontext);
                }
            } else {
                // ... if there is a prefix to generate a document name, prepare it from the offset and rowIndex
                name = defaultPrefix + (config.getEmptyDocNameOffset() + rowIndex);
            }
        }

        if (clearNames) {
            XWikiContext xcontext = getXWikiContext();
            name = xcontext.getWiki().clearName(name, xcontext);
        }

        DocumentReference pageName = prepareDocumentReference(wiki, space, name);

        // prepare the document name if it's duplicate and needs to be deduplicated
        if (config.getDocNameDeduplication() == Overwrite.GENERATE_NEW && docNameList.contains(pageName)) {
            String initialName = pageName.getName();
            int counter = 0;
            while (docNameList.contains(pageName)) {
                counter++;
                pageName = prepareDocumentReference(wiki, space, initialName + counter);
            }
        }

        return pageName;
    }

    /**
     * Deduplicate page name amongst the documents that are on the same wiki.
     *
     * @return the potentially deduplicated page name, according to the parameters in the config. Note that it can also
     *         return the very same {@code pageName} parameter.
     */
    public DocumentReference maybeDeduplicatePageNameInWiki(DocumentReference pageName,
        BatchImportConfiguration config, List<DocumentReference> savedDocuments, XWikiContext xcontext)
    {
        if (pageName == null) {
            return pageName;
        }

        String wiki = pageName.getWikiReference().getName();
        String space = pageName.getLastSpaceReference().getName();

        // verify if it should be unique in the wiki and if it is
        if (config.getOverwrite() == Overwrite.GENERATE_NEW) {
            String deduplicatedName = pageName.getName();
            int counter = 0;
            // if the document exists already, generate a new name
            while (xcontext.getWiki().exists(pageName, xcontext)) {
                if (savedDocuments.contains(pageName) && config.getDocNameDeduplication() == Overwrite.UPDATE) {
                    // if the document exists because it was saved this round and we're using deduplication strategy
                    // update, leave this name, it's good
                    break;
                }
                counter++;
                pageName = prepareDocumentReference(wiki, space, deduplicatedName + "_" + counter);
            }
        }

        return pageName;
    }

    protected DocumentReference prepareDocumentReference(String wiki, String space, String name)
    {
        // prepare a space reference from the passed wiki and the passed space: resolve the space as a space reference
        // so that we also support nested spaces.

        // prepare relative wiki reference to serve as a root for the space reference
        WikiReference docWikiReference = null;
        if (!StringUtils.isEmpty(wiki)) {
            // specified wiki, put it in there
            docWikiReference = new WikiReference(wiki);
        }

        SpaceReference docSpaceReference =
            new SpaceReference(currentStringResolver.resolve(space, EntityType.SPACE, docWikiReference));

        return new DocumentReference(name, docSpaceReference);
    }

    public String getFilePath(String datadir, String datadirprefix, String filename)
    {
        if (StringUtils.isEmpty(datadir) || datadir.equals(".")) {
            datadir = "";
        } else if (!datadir.endsWith("/")) {
            datadir = datadir + "/";
        }

        if (StringUtils.isEmpty(datadirprefix)) {
            datadirprefix = "";
        } else if (!datadirprefix.endsWith("/")) {
            datadirprefix = datadirprefix + "/";
        }
        if (filename.startsWith("./")) {
            filename = filename.substring(2);
        }

        String path = datadir + datadirprefix + filename;
        return path;
    }

    public boolean checkFile(ZipFile zipfile, String path)
    {
        if (zipfile == null) {
            debug("Checking if file " + path + " exists on disk");

            File file = new File(path);
            return file.exists();
        } else {
            String newpath = path;
            ZipEntry zipentry = zipfile.getEntry(newpath);

            debug("Checking if file " + newpath + " exists in zip");
            return (zipentry != null);
        }
    }

    /**
     * This method is preserved only because it's public, but it's not used anymore by the importer since 2.4.
     */
    @Deprecated
    public String getFileName(String filename)
    {
        if (filename.startsWith("./")) {
            filename = filename.substring(2);
        }
        XWikiContext xcontext = getXWikiContext();
        filename = xcontext.getWiki().clearName(filename, false, true, xcontext);
        return filename;
    }

    public boolean isDirectory(ZipFile zipfile, String path)
    {
        if (zipfile == null) {
            return new File(path).isDirectory();
        } else {
            return zipfile.getEntry(path).isDirectory();
        }
    }

    /**
     * Signature preserved only because the method is public, use the method  below.
     */
    @Deprecated
    public void addFiles(Document newDoc, String path) throws IOException
    {
        addFiles(newDoc, path, true);
    }

    public void addFiles(Document newDoc, String path, boolean clearName) throws IOException
    {
        File dirFile = new File(path);
        for (File file : dirFile.listFiles()) {
            debug("Adding file " + file.getName());
            InputStream filedata = null;
            try {
                filedata = new FileInputStream(file.getPath());
                addFile(newDoc, filedata, file.getName(), clearName);
            } finally {
                if (filedata != null) {
                    filedata.close();
                }
            }
        }
    }

    public void addFiles(Document newDoc, Collection<String> paths, boolean clearName) throws IOException
    {
        for (String filePath : paths) {
            File file = new File(filePath);
            debug("Adding file " + file.getName());
            InputStream filedata = null;
            try {
                filedata = new FileInputStream(file.getPath());
                addFile(newDoc, filedata, file.getName(), clearName);
            } finally {
                if (filedata != null) {
                    filedata.close();
                }
            }
        }
    }

    /**
     * Signature preserved only because the method is public, use the method  below.
     */
    @Deprecated
    public void addFile(Document newDoc, byte[] filedata, String filename)
    {
        addFile(newDoc, new ByteArrayInputStream(filedata), filename, true);
    }

    @SuppressWarnings("deprecation")
    public void addFile(Document newDoc, InputStream filedata, String filename, boolean clearName)
    {
        try {
            if (filename.startsWith("./")) {
                filename = filename.substring(2);
            }
            XWikiContext xcontext = getXWikiContext();
            if (clearName) {
                filename = xcontext.getWiki().clearName(filename, false, true, xcontext);
            }

            if (newDoc.getAttachment(filename) != null) {
                debug("Filename " + filename + " already exists in " + newDoc.getPrefixedFullName() + ".");
                return;
            }

            // this is saving the document at this point. I don't know if it was like this when the code was written,
            // but now it's like this.
            // WARNING: this will work only if the performer of the import has programming rights.
            XWikiDocument protectedDocument = newDoc.getDocument();
            XWikiAttachment attachment = new XWikiAttachment();
            attachment.setContent(filedata);
            attachment.setFilename(filename);
            attachment.setAuthor(xcontext.getUser());
            // Add the attachment to the document
            attachment.setDoc(protectedDocument);
            protectedDocument.getAttachmentList().add(attachment);
        } catch (Throwable e) {
            debug("Filename " + filename + " could not be attached because of Exception: " + e.getMessage());
        }
    }

    public InputStream getFileData(ZipFile zipfile, String path) throws ZipException, IOException
    {
        if (zipfile == null) {
            return new FileInputStream(path);
        } else {
            String newpath = path;
            ZipEntry zipentry = zipfile.getEntry(newpath);
            if (zipentry == null) {
                return null;
            }
            return zipfile.getInputStream(zipentry);
        }
    }

    /**
     * Cleans up tmp folder after the work of office importer.
     */
    public void cleanUp()
    {
        File tmpdir = new File("/tmp/");
        for (File tmpsubdir : tmpdir.listFiles()) {
            if (tmpsubdir.getName().startsWith("sv") && tmpsubdir.getName().endsWith(".tmp")) {
                for (File file : tmpsubdir.listFiles()) {
                    file.delete();
                }
                tmpsubdir.delete();
            }
        }
    }

    @Override
    public BatchImportLog doImport(BatchImportConfiguration config, boolean withFiles, boolean overwritefile,
        boolean simulation) throws IOException, XWikiException
    {
        return this.doImport(config, withFiles, overwritefile, simulation, null);
    }

    public BatchImportLog doImport(BatchImportConfiguration config, boolean withFiles, boolean overwritefile,
        boolean simulation, String logHint) throws IOException, XWikiException
    {
        XWikiContext xcontext = getXWikiContext();
        XWiki xwiki = xcontext.getWiki();

        // log to report how this import goes
        BatchImportLog log = getLog(logHint);

        // the file to import
        ImportFileIterator metadatafilename = null;
        try {
            metadatafilename = getImportFileIterator(config);
        } catch (ComponentLookupException e) {
            // IOException directly from the getFileIterator method
            throw new IOException("Could not find an import file reader for the configuration: " + config.toString(), e);
        }
        // mapping from the class fields to source file columns
        Map<String, String> mapping = config.getFieldsMapping();

        // -------------------- Not transformed to config yet, will not work ---------------------//
        Document doc = new Document(xcontext.getDoc(), xcontext);
        // attach files referred in the column _file to the document
        boolean fileupload = getBooleanValue(doc, "fileupload", false);

        // use office importer to import the content from the column _file to the document content
        boolean fileimport = getBooleanValue(doc, "fileimport", false);

        // use office importer to import the content from the column _file to the document content
        boolean filterstyles = getBooleanValue(doc, "filterstyles", true);

        // directory or zip file where the referenced files are stored. Directory on disk.
        String datadir = (String) doc.getValue("datafilename");
        // path of the files inside the zip
        String datadirprefix = (String) doc.getValue("datafileprefix");
        // column in the xls that will turn into tags
        // TODO: this tags needs to be reimplemented, now it works only with xwiki fields in the list: so you can
        // add something in the tags only if you import it as well. You should be able to configure it to be a
        // column in the csv / xls and that column needs to be handled as a list with the list separator. Implement as
        // doc.tags
        List<String> fieldsfortags = getAsList((String) doc.getValue("fieldsfortags"), config.getListSeparator());
        // -------------------- ----------------------------- ---------------------//

        // class to map data to (objects of this class will be created)
        BaseClass defaultClass =
            xwiki.getXClass(
                currentDocumentStringResolver.resolve(config.getMappingClassName(),
                    StringUtils.isEmpty(config.getWiki()) ? null : new WikiReference(config.getWiki())), xcontext);
        // TODO: validate that mapping is correct on top of this class, issue an "Error" if not (more of a warning than
        // an error): check that fields exist, etc
        // the locale of the data to read
        Locale sourceLocale = config.getLocale();
        // prepare the default date formatter to process the dates in this row
        DateFormat defaultDateFormatter = null;
        String defaultDateFormat = config.getDefaultDateFormat();
        if (StringUtils.isEmpty(defaultDateFormat)) {
            if (sourceLocale != null) {
                defaultDateFormatter = DateFormat.getDateInstance(DateFormat.MEDIUM, sourceLocale);
            }
            // get it from preferences, hoping that it's set
            defaultDateFormat = xcontext.getWiki().getXWikiPreference("dateformat", xcontext);
        }
        defaultDateFormatter = new SimpleDateFormat(defaultDateFormat);

        // and get a number formatter, we'll use this to process the numbers a bit.
        NumberFormat numberFormatter = null;
        if (sourceLocale != null) {
            numberFormatter = NumberFormat.getInstance(sourceLocale);
        }

        // whether this file has header row or not (whether first line needs to be imported or not)
        boolean hasHeaderRow = config.hasHeaderRow();

        // list of document names, used to remember what are the document names that were generated from the
        // document. Note that for multiple imports from the same document, this list should be identical.
        List<DocumentReference> docNameList = new ArrayList<DocumentReference>();

        // list of documents that were actually saved during this import, to know how to make proper replacements.
        // Basically it serves to know if a document which is not new was saved before during this import or it was
        // there before the import started. This prevents "replace" from deleting twice (if multiple rows with the
        // same name are supposed to update each other) and allows to save multiple rows in the same document if
        // overwrite is set to skip and the document is created during this import (in which case duplicate rows
        // should not "skip" but "update").
        List<DocumentReference> savedDocuments = new ArrayList<DocumentReference>();

        // prepare the list of data postprocessors that are available
        List<RowDataPostprocessor> dataProcessors;
        try {
            dataProcessors = this.getRowDataPostprocessors();
        } catch (ComponentLookupException e) {
            throw new XWikiException(0, 0, "Could not fetch data postprocessors for the batch import", e);
        }

        ZipFile zipfile = null;
        // "withFiles" is true when there are input files that need to be read. This is the case when either
        // "fileupload" or "fileimport" is true.
        if ((!fileimport && !fileupload) || StringUtils.isEmpty(datadir)) {
            withFiles = false;
        }

        // check if the files in the datadir can be properly read
        if (withFiles) {
            // if it's a zip, try to read the zip
            if (datadir.endsWith(".zip")) {
                log.log("checkingzip", datadir);
                try {
                    zipfile = new ZipFile(new File(datadir));
                    // TODO: what the hell is this, why are we putting it on empty?
                    datadir = "";
                } catch (IOException e) {
                    log.logError("cannotopenzip", datadir);
                    return log;
                }

                if (debug) {
                    @SuppressWarnings("unchecked")
                    Enumeration< ? extends ZipEntry> zipFileEntries = zipfile.entries();
                    while (zipFileEntries.hasMoreElements()) {
                        ZipEntry zipe = zipFileEntries.nextElement();
                        debug("Found zip entry: " + zipe.getName());
                    }
                }
            } else {
                // checking it as a directory
                log.log("checkingdatadir", datadir);
                File datad = new File(datadir);
                if (datad == null || !datad.isDirectory()) {
                    log.logError("cannotopendatadir", datadir);
                    return log;
                }
            }
        }

        // start reading the rows and process them one by one
        metadatafilename.resetFile(config);
        List<String> currentLine = null;
        int rowIndex = 0;
        List<String> headers = null;
        // if there is no header row the headers are the numbers of the columns as strings
        if (hasHeaderRow) {
            headers = getColumnHeaders(metadatafilename, hasHeaderRow);
            currentLine = metadatafilename.readNextLine();
            rowIndex = 1;
        } else {
            currentLine = metadatafilename.readNextLine();
            headers = new ArrayList<String>();
            for (int i = 0; i < currentLine.size(); i++) {
                headers.add(Integer.toString(i));
            }
        }

        debug("Headers are: " + headers);
        debug("Mapping is: " + mapping);

        while (currentLine != null) {
            debug("Processing row " + rowIndex + ".");

            Map<String, String> data = getData(currentLine, mapping, headers);
            if (data == null) {
                break;
            }

            debug("Row " + currentLine.toString() + " data is: " + data.toString() + "");
            // postprocess the data with visitors (all registered visitors). The order of the visitors is the one of the
            // priorities
            for (RowDataPostprocessor proc : dataProcessors) {
                proc.postProcessRow(data, currentLine, rowIndex, mapping, headers, config);
            }
            debug("Row " + currentLine.toString() + " data (after postprocessing) is: " + data.toString() + "");
            // generate page name
            DocumentReference generatedDocName = getPageName(data, rowIndex, config, docNameList);
            // process the row
            if (generatedDocName != null) {
                // check if it's duplicated name
                boolean isDuplicateName = docNameList.contains(generatedDocName);
                if (!isDuplicateName) {
                    docNameList.add(generatedDocName);
                }
                // check that this pageName should be used from the pov of the already generated file names
                if (!(isDuplicateName && config.getDocNameDeduplication() == Overwrite.SKIP)) {
                    // potentially deduplicate it on the wiki, if needed
                    DocumentReference pageName =
                        maybeDeduplicatePageNameInWiki(generatedDocName, config, savedDocuments, xcontext);
                    // validate the page name (check if it fits in the xwiki db, length of fullName, etc)
                    boolean pageNameValid =
                        validatePageName(pageName, rowIndex, currentLine, mapping, data, simulation, log);
                    if (pageNameValid) {
                        // try catch here, in case a row fails to save because of xwiki issues, we go to next row
                        try {
                            // marshal data to the document objects (this is creating the document and handling
                            // overwrites)
                            Document newDoc =
                                this.marshalDataToDocumentObjects(pageName, data, currentLine, rowIndex, defaultClass,
                                    isDuplicateName, savedDocuments.contains(pageName), config, xcontext,
                                    fieldsfortags, defaultDateFormatter, numberFormatter, log, simulation);
                            // if a new document was created and filled, valid, with the proper overwrite
                            if (newDoc != null) {
                                // save the document ...
                                if (withFiles) {
                                    // ... either with its files. Saving is done in the same function as files saving
                                    // there are reasons to do multiple saves when saving attachments and importing
                                    // office
                                    // documents, so we rely completely on files for saving.
                                    // TODO: fix the overwrite parameter, for now pass false if it's set to anything
                                    // else
                                    // besides skip
                                    saveDocumentWithFiles(newDoc, data, currentLine, rowIndex, config, xcontext,
                                        config.getOverwrite() != Overwrite.SKIP, simulation, overwritefile, fileimport,
                                        fileupload, datadir, datadirprefix, zipfile, filterstyles, savedDocuments, log);
                                } else {
                                    // ... or just save it: no files handling it, we save it here manually
                                    String serializedPageName = entityReferenceSerializer.serialize(pageName);
                                    if (!simulation) {
                                        newDoc.save();
                                        log.logSave("import", rowIndex, currentLine, serializedPageName);
                                    } else {
                                        // NOTE: when used with overwrite=GENERATE_NEW, this line here can yield results
                                        // a
                                        // bit different from the actual results during the import, since, if a document
                                        // fails to save with an exception, the simulation thinks it actually saved,
                                        // while
                                        // the actual import knows it didn't.
                                        log.logSave("simimport", rowIndex, currentLine, serializedPageName);
                                    }
                                    savedDocuments.add(newDoc.getDocumentReference());
                                }
                            } else {
                                // newDoc is null
                                // validation error during page generation, page generation and validation is
                                // responsible to log
                            }
                        } catch (XWikiException xwe) {
                            log.logCritical("importfail", rowIndex, currentLine, pageName, xwe);
                            LOGGER.warn("Failed to import line " + currentLine + " to document " + pageName, xwe);
                        } catch (IOException ioe) {
                            log.logCritical("importfail", rowIndex, currentLine, pageName, ioe);
                            LOGGER.warn("Failed to import line " + currentLine + " to document " + pageName, ioe);
                        }
                    } else {
                        // page name not valid, doesn't fit in the db. If we're simulating, validate the rest as well to
                        // show all errors at once
                        if (simulation) {
                            parseAndValidatePageData(data, rowIndex, currentLine,
                                this.entityReferenceSerializer.serialize(pageName), defaultClass, config,
                                defaultDateFormatter, numberFormatter, simulation, log, false);
                        }
                        // don't log, the validation functions are logging
                    }
                } else {
                    // pageName exists and the config is set to ignore
                    log.logSkip("ignoreduplicate", rowIndex, currentLine);
                }
            } else {
                // pageName is null
                log.logSkip("ignoreemptypagename", rowIndex, currentLine);
            }

            // go to next line
            currentLine = metadatafilename.readNextLine();
            rowIndex++;
        }

        log.log("done");
        // set saved documents to the log, if it knows how to accept them
        if (log instanceof AbstractSavedDocumentsBatchImportLog) {
            ((AbstractSavedDocumentsBatchImportLog) log).setSavedDocuments(savedDocuments);
        }

        return log;
    }

    @Override
    public BatchImportLog deleteExistingDocuments(String className, String wiki, String space, String logHint)
        throws XWikiException
    {
        XWikiContext xcontext = getXWikiContext();
        XWiki xwiki = xcontext.getWiki();

        BatchImportLog log = getLog(logHint);

        String originalDatabase = xcontext.getDatabase();
        try {
            if (!StringUtils.isEmpty(wiki)) {
                xcontext.setDatabase(wiki);
            }

            // get the documents
            String searchQuery =
                "select doc.fullName from XWikiDocument doc, BaseObject obj "
                    + "where doc.fullName = obj.name and obj.className = ? and doc.fullName != ? and doc.fullName != ?";
            List<String> parameterValues = new ArrayList<String>();
            parameterValues.add(className);
            parameterValues.add(className + "Template");
            parameterValues.add(className.substring(0, className.indexOf("Class") >= 0 ? className.indexOf("Class")
                : className.length())
                + "Template");
            if (!StringUtils.isEmpty(space)) {
                // add space condition
                searchQuery += " and doc.space = ?";
                parameterValues.add(space);
            }

            List<String> results = xwiki.getStore().search(searchQuery, 0, 0, parameterValues, xcontext);
            for (String docToDelete : results) {
                try {

                    DocumentReference docToDeleteRef =
                        currentDocumentStringResolver.resolve(docToDelete, StringUtils.isEmpty(wiki) ? null
                            : new WikiReference(wiki));
                    // this is the way to delete with the proper user (current user that is), as if the delete occurred
                    // from page
                    new Document(xwiki.getDocument(docToDeleteRef, xcontext), xcontext).delete();
                    log.logDelete("delete", docToDelete, wiki);
                } catch (XWikiException e) {
                    log.logCritical("deletefail", docToDelete, wiki, e);
                    LOGGER.warn("Failed to delete document " + docToDelete + " from wiki " + wiki, e);
                }
            }

            return log;
        } finally {
            if (!StringUtils.isEmpty(wiki)) {
                xcontext.setDatabase(originalDatabase);
            }
        }
    }

    @Override
    public BatchImportLog deleteExistingDocuments(String className, String wiki, String space) throws XWikiException
    {
        return this.deleteExistingDocuments(className, wiki, space, null);
    }

    protected boolean checkLength(int rowIndex, List<String> currentLine, String fullName, String fieldName,
        String column, String value, int maxLength, BatchImportLog log)
    {
        if (value.length() > maxLength) {
            log.logError("errorvalidationlength", rowIndex, currentLine, fullName, fieldName, column, value, maxLength);
            return false;
        }
        return true;
    }

    /**
     * Check the length of this page name since, hibernate mapping wise, we only can put 255 characters on the fullName
     * TODO: use simulation param to stop at first error
     */
    public boolean validatePageName(DocumentReference docName, int rowIndex, List<String> currentLine,
        Map<String, String> mapping, Map<String, String> data, boolean simulation, BatchImportLog log)
    {
        boolean hasPageNameError = false;

        String fullPrefixedName = this.entityReferenceSerializer.serialize(docName);

        // check first doc.name, doc.space and then together. Display all even if redundant, so that user can see
        hasPageNameError =
            !checkLength(rowIndex, currentLine, fullPrefixedName, "doc.name", mapping.get("doc.name"),
                docName.getName(), 255, log)
                || hasPageNameError;

        hasPageNameError =
            !checkLength(rowIndex, currentLine, fullPrefixedName, "doc.space", mapping.get("doc.space"), docName
                .getLastSpaceReference().getName(), 255, log)
                || hasPageNameError;

        String fullNameForDb = this.localEntityReferenceSerializer.serialize(docName);
        if (fullNameForDb.length() > 255) {
            log.logError("errorvalidationlengthdocfullname", rowIndex, currentLine, fullNameForDb, 255);
            hasPageNameError = true;
        }

        return !hasPageNameError;
    }

    /**
     * Validate that an object can be created in this document, to prevent continuing if it's not the case.
     *
     * @throws XWikiException
     */
    public boolean validateCanCreateObject(Document newDoc, int rowIndex, List<String> currentLine,
        BaseClass defaultClass, boolean simulation, BatchImportLog log)
    {
        // try to get the object of the class type in the document. If we cannot make it, then class is
        // "invalid"
        String className = this.entityReferenceSerializer.serialize(defaultClass.getDocumentReference());
        com.xpn.xwiki.api.Object newDocObj = null;
        newDocObj = newDoc.getObject(className);
        if (newDocObj == null) {
            try {
                newDocObj = newDoc.newObject(className);
            } catch (XWikiException e) {
                log.logError("errorvalidationnoobject", rowIndex, currentLine, newDoc.getPrefixedFullName(), className);
                // log the exception here, since it would be something that we need to debug and we would have no way
                // otherwise
                LOGGER.warn("Exception encountered while getting an object of class " + className + " for document "
                    + newDoc.getDocumentReference(), e);
                return false;
            }
        }
        if (newDocObj == null) {
            log.logError("errorvalidationnoobject", rowIndex, currentLine, newDoc.getPrefixedFullName(), className);
            return false;
        }

        return true;
    }

    public Map<String, Object> parseAndValidatePageData(Map<String, String> data, int rowIndex,
        List<String> currentLine, String fullName, BaseClass defaultClass, BatchImportConfiguration config,
        DateFormat defaultDateFormatter, NumberFormat numberFormatter, boolean simulation, BatchImportLog log,
        boolean forceReturnParsed)
    {
        Map<String, String> mapping = config.getFieldsMapping();
        Character listSeparator = config.getListSeparator();

        Map<String, Object> parsedData = new HashMap<String, Object>();

        // TODO: use the simulation parameter to stop early in the verification, if it's not simulation to stop on first
        // error
        boolean hasValidationError = false;

        // now get all the fields in the data and check types and lengths
        for (Map.Entry<String, String> dataEntry : data.entrySet()) {
            String fieldName = dataEntry.getKey();
            String column = mapping.get(fieldName);
            String value = dataEntry.getValue();
            // this will be reassigned while validating formats, and added in the parsed data map at the end
            Object parsedValue = value;

            // skip empty columns -> put actual empty in the parsedData, so that we can distinguish null (== invalid)
            // from empty ( == empty string)
            if (StringUtils.isEmpty(value)) {
                parsedData.put(fieldName, "");
                continue;
            }
            if (fieldName.startsWith("doc.")) {
                // no restrictions, only restrictions are about length for title, parent,
                if (fieldName.equals("doc.title")) {
                    // limit at 255
                    if (!checkLength(rowIndex, currentLine, fullName, fieldName, column, value, 255, log)) {
                        hasValidationError = true;
                        parsedValue = null;
                    }
                }
                if (fieldName.equals("doc.parent")) {
                    // limit at 511
                    if (!checkLength(rowIndex, currentLine, fullName, fieldName, column, value, 511, log)) {
                        hasValidationError = true;
                        parsedValue = null;
                    }
                }
                if (fieldName.equals("doc.content")) {
                    // limit at 200000
                    if (!checkLength(rowIndex, currentLine, fullName, fieldName, column, value, 200000, log)) {
                        hasValidationError = true;
                        parsedValue = null;
                    }
                }
                if (fieldName.equals("doc.file")) {
                    // we don't really validate much more (although we could try), we just parse as list
                    parsedValue = getAsList(value, config.getListSeparator());
                }
                // TODO: check doc.tags (but I don't know exactly how). For now, doc.tags is not even collected in the
                // data map, so we cannot check it here
            } else {
                // get the property from the class and validate it
                PropertyInterface prop = defaultClass.get(fieldName);

                if (prop instanceof StringClass && !(prop instanceof TextAreaClass)) {
                    // check length, 255
                    if (!checkLength(rowIndex, currentLine, fullName, fieldName, column, value, 255, log)) {
                        hasValidationError = true;
                        parsedValue = null;
                    }
                }
                // textarea
                if (prop instanceof TextAreaClass) {
                    // check length, 60 000
                    if (!checkLength(rowIndex, currentLine, fullName, fieldName, column, value, 60000, log)) {
                        hasValidationError = true;
                        parsedValue = null;
                    }
                }

                // we start checking types now and actually parsing string values to something else, depending on the
                // property type
                if (prop instanceof BooleanClass) {
                    // this is a bit annoying for boolean, but let's make it, otherwise it might be too magic
                    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")
                        && !value.equalsIgnoreCase("0") && !value.equalsIgnoreCase("1")) {
                        log.logError("errorvalidationtypeboolean", rowIndex, currentLine, fullName, fieldName, column,
                            value);
                        hasValidationError = true;
                        parsedValue = null;
                    } else {
                        if (value.equalsIgnoreCase("1")) {
                            parsedValue = Boolean.TRUE;
                        } else {
                            // this will be true only when the string is "true" so for both "0" and "false" it will be
                            // false
                            parsedValue = Boolean.parseBoolean(value);
                        }
                    }
                }

                if (prop instanceof NumberClass) {
                    // we have 4 cases here, with type and length for each of them
                    String ntype = ((NumberClass) prop).getNumberType();
                    String numberString = value;
                    if (numberFormatter != null && numberFormatter instanceof DecimalFormat) {
                        // There is a number formatter, so we need to clean a bit the value before parsing it, from a
                        // locale pov.
                        // Note: We tried NumberFormat#parse, but it's very complicated since number format can stop
                        // earlier than the end of a string if it's encountering a character it doesn't know, so for
                        // locale french it would stop on a dot (123.45 will return 123 as answer). We can know if it
                        // stopped earlier, but then we need to check why and make sense of the rest (is it an error or
                        // we're fine with the fact that it stopped earlier? e.g. in the case of a percent, 123,34%,
                        // it's ok that it stops earlier, we like it).
                        // So nicer solution here, we'll just take some common symbols from the locale and remove them
                        // or replace them with the standard number separator (e.g. decimal point always goes to .,
                        // thousands separator always goes to nothing, same for percent, same for currencies) and then
                        // parse this as a standard number.
                        DecimalFormat numberDecimalFormatter = (DecimalFormat) numberFormatter;
                        // decimal separator turns into dot
                        numberString =
                            numberString.replaceAll(Pattern.quote(((Character) numberDecimalFormatter
                                .getDecimalFormatSymbols().getDecimalSeparator()).toString()), ".");
                        // thousands separator turns into nothing
                        numberString =
                            numberString.replaceAll(Pattern.quote(((Character) numberDecimalFormatter
                                .getDecimalFormatSymbols().getGroupingSeparator()).toString()), "");
                        // currency turns into nothing
                        numberString =
                            numberString
                                .replaceAll(
                                    Pattern.quote(numberDecimalFormatter.getDecimalFormatSymbols().getCurrencySymbol()),
                                    "");
                        numberString =
                            numberString.replaceAll(Pattern.quote(numberDecimalFormatter.getDecimalFormatSymbols()
                                .getInternationalCurrencySymbol()), "");
                        // percent, per mill turn into nothing
                        numberString =
                            numberString.replaceAll(Pattern.quote(((Character) numberDecimalFormatter
                                .getDecimalFormatSymbols().getPercent()).toString()), "");
                        numberString =
                            numberString.replaceAll(Pattern.quote(((Character) numberDecimalFormatter
                                .getDecimalFormatSymbols().getPerMill()).toString()), "");
                        // minus turns into minus
                        numberString =
                            numberString.replaceAll(Pattern.quote(((Character) numberDecimalFormatter
                                .getDecimalFormatSymbols().getMinusSign()).toString()), "-");
                        // and remove all other spaces that might be left
                        numberString = numberString.trim();
                    }
                    // I could use NumberClass#fromString, but it's logging as if one has introduced the value from
                    // the UI, which I don't like.
                    try {
                        if (ntype.equals("integer")) {
                            parsedValue = new Integer(numberString);
                        } else if (ntype.equals("float")) {
                            // FIXME: check that this constructor is not truncating when the value is too long
                            parsedValue = new Float(numberString);
                        } else if (ntype.equals("double")) {
                            parsedValue = new Double(numberString);
                        } else {
                            parsedValue = new Long(numberString);
                        }
                    } catch (NumberFormatException nfe) {
                        hasValidationError = true;
                        parsedValue = null;
                        log.logError("errorvalidationtype" + ntype, rowIndex, currentLine, fullName, fieldName, column,
                            value);
                    }
                    // TODO: check some intervals, for some values which are available in java, but not in mysql (java
                    // takes more than mysql, iirc)
                }

                if (prop instanceof DateClass) {
                    debug("Found date " + value + " for key -" + fieldName + "-");
                    String datePropFormat = ((DateClass) prop).getDateFormat();
                    SimpleDateFormat sdf = new SimpleDateFormat(datePropFormat);
                    try {
                        parsedValue = sdf.parse(value);
                    } catch (ParseException exc) {
                        // try to parse with the default date then
                        try {
                            parsedValue = defaultDateFormatter.parse(value);
                        } catch (ParseException e) {
                            // now we cannot do much more
                            hasValidationError = true;
                            parsedValue = null;
                            log.logError("errorvalidationtypedate", rowIndex, currentLine, fullName, fieldName, column,
                                value, datePropFormat);
                            debug("Failed to parse date " + value + " for key " + fieldName);
                        }
                    }
                }

                if (prop instanceof ListClass) {
                    if (((ListClass) prop).isMultiSelect()) {
                        List<String> listValues = getAsList(value, listSeparator);
                        parsedValue = listValues;
                        if (((ListClass) prop).isRelationalStorage()) {
                            // check values one by one if they have appropriate length, namely 255
                            // NOTE that this 255 is not mentioned anywhere, it's the mysql reality (5.1), observed on
                            // my machine, since there is no explicit information in the hibernate file for
                            // xwikilistitems table
                            for (String splitValue : listValues) {
                                if (!checkLength(rowIndex, currentLine, fullName, fieldName, column, splitValue, 255,
                                    log)) {
                                    hasValidationError = true;
                                    parsedValue = null;
                                }
                            }
                        } else {
                            // stored as StringListProperty, limit at 60 000
                            if (!checkLength(rowIndex, currentLine, fullName, fieldName, column, value, 60000, log)) {
                                hasValidationError = true;
                                parsedValue = null;
                            }
                        }
                    } else {
                        // stored as StringProperty, limit at 255
                        if (!checkLength(rowIndex, currentLine, fullName, fieldName, column, value, 255, log)) {
                            hasValidationError = true;
                            parsedValue = null;
                        }
                    }
                }
            }

            // and finally put the data in the parsed data map
            parsedData.put(fieldName, parsedValue);
        }

        // if either we don't have validation error or we force the return of the parsed data, return the data,
        // otherwise null.
        if (!hasValidationError || forceReturnParsed) {
            return parsedData;
        } else {
            return null;
        }
    }

    public Document marshalDataToDocumentObjects(DocumentReference pageName, Map<String, String> data,
        List<String> currentLine, int rowIndex, BaseClass defaultClass, boolean isRowUpdate, boolean wasAlreadySaved,
        BatchImportConfiguration config, XWikiContext xcontext, List<String> fieldsfortags,
        DateFormat defaultDateFormatter, NumberFormat numberFormatter, BatchImportLog log, boolean simulation)
        throws XWikiException
    {
        XWiki xwiki = xcontext.getWiki();
        String className = this.entityReferenceSerializer.serialize(defaultClass.getDocumentReference());
        Map<String, String> mapping = config.getFieldsMapping();
        Overwrite overwrite = config.getOverwrite();

        String fullName = entityReferenceSerializer.serialize(pageName);

        Document newDoc = xwiki.getDocument(pageName, xcontext).newDocument(xcontext);
        // if either the document is new
        // or is not new but we're not supposed to skip
        // or it's existing, we're supposed to skip but it was saved during this import and this is an update row
        if (newDoc.isNew() || overwrite != Overwrite.SKIP || (wasAlreadySaved && isRowUpdate)) {

            // validate the data to marshal in this page. Errors will be logged by the validation function
            Map<String, Object> parsedRow =
                parseAndValidatePageData(data, rowIndex, currentLine, fullName, defaultClass, config,
                    defaultDateFormatter, numberFormatter, simulation, log, false);
            boolean validateObjectCreation =
                validateCanCreateObject(newDoc, rowIndex, currentLine, defaultClass, simulation, log);
            if (parsedRow == null || !validateObjectCreation) {
                return null;
            }

            // if document is not new and we're in replace mode, and the document was not already saved during this
            // import, we remove it
            if (!newDoc.isNew() && overwrite == Overwrite.REPLACE && !wasAlreadySaved) {
                if (!simulation) {
                    newDoc.delete();
                    // reload the reference so that it doesn't keep a reference to the old document
                    newDoc = xwiki.getDocument(pageName, xcontext).newDocument(xcontext);

                    log.logDelete("toreplace", rowIndex, currentLine, fullName);
                } else {
                    log.logDelete("simtoreplace", rowIndex, currentLine, fullName);
                }
            }

            // get the object, it should be non-null here since we validated that we can do that in the validation step
            // above the removal. We need to do this here, under the removal, to be able to grab a fresh object if we
            // needed to remove document.
            com.xpn.xwiki.api.Object newDocObj = null;
            newDocObj = newDoc.getObject(className);
            if (newDocObj == null) {
                newDocObj = newDoc.newObject(className);
            }

            List<String> tagList = new ArrayList<String>();
            for (String key : mapping.keySet()) {
                Object value = parsedRow.get(key);
                String stringValue = data.get(key);
                if (!key.startsWith("doc.")) {
                    PropertyInterface prop = defaultClass.get(key);

                    if (!StringUtils.isEmpty(stringValue) || config.getHonorEmptyValues()) {
                        boolean addtotags = false;

                        if (fieldsfortags.contains(key) || fieldsfortags.contains("ALL")) {
                            addtotags = true;
                        }
                        if (prop instanceof ListClass && (((ListClass) prop).isMultiSelect())) {
                            // make this check, although value should really be instanceof List at this point since it
                            // was properly prepared by the parseAndValidatePageData function
                            if (addtotags && value instanceof List) {
                                tagList.addAll((List) value);
                            }
                            newDocObj.set(key, value);
                        } else if (prop instanceof BooleanClass && value instanceof Boolean) {
                            // beautiful special case here, set for boolean properties expects number in xwiki, not
                            // boolean, so let's just pass it properly.
                            if ((Boolean) value) {
                                newDocObj.set(key, 1);
                            } else {
                                newDocObj.set(key, 0);
                            }
                            if (addtotags) {
                                tagList.add(stringValue);
                            }
                        } else {
                            newDocObj.set(key, value);

                            if (addtotags) {
                                tagList.add(stringValue);
                            }
                        }
                    }
                } else if (key.equals("doc.file")) {
                    // ignore, will be handled by the file function
                } else if (key.equals("doc.title")) {
                    // Override the title only if the provided value is not empty or if the import should honor empty
                    // cells
                    if (!StringUtils.isEmpty(stringValue) || config.getHonorEmptyValues()) {
                        newDoc.setTitle((String) value);
                    }
                } else if (key.equals("doc.parent")) {
                    if (!StringUtils.isEmpty(stringValue) || config.getHonorEmptyValues()) {
                        newDoc.setParent((String) value);
                    }
                } else if (key.equals("doc.content")) {
                    if (!StringUtils.isEmpty(stringValue) || config.getHonorEmptyValues()) {
                        newDoc.setContent((String) value);
                    }
                }
            }

            // set tags, only if needed.
            // TODO: fix this test here, it should depend on an "empty overwrites" parameter, which should say whether
            // an empty value is considered significant or not, which should also apply to properties: for now we
            // overwrite it even with an empty tagList, if the fields for tags is set to something and that something is
            // void, maybe we shouldn't
            if (fieldsfortags != null && fieldsfortags.size() > 0) {
                com.xpn.xwiki.api.Object newTagsObject = newDoc.getObject("XWiki.TagClass");
                if (newTagsObject == null) {
                    newTagsObject = newDoc.newObject("XWiki.TagClass");
                }
                newTagsObject.set("tags", tagList);
            }

            // set a parent if a parent is empty after import
            // TODO: make this a config parameter
            if (StringUtils.isEmpty(newDoc.getParent()) && !config.getHonorEmptyValues()) {
                // to the webHome of its space
                newDoc.setParent("WebHome");
            }

            // polish a bit the content of the document
            if (StringUtils.isEmpty(newDoc.getContent().trim())) {
                newDoc.setContent("");
            }

        } else {
            log.logSkip("ignorealreadyexists", rowIndex, currentLine, fullName);
            return null;
        }

        return newDoc;
    }

    public void saveDocumentWithFiles(Document newDoc, Map<String, String> data, List<String> currentLine,
        int rowIndex, BatchImportConfiguration config, XWikiContext xcontext, boolean overwrite, boolean simulation,
        boolean overwritefile, boolean fileimport, boolean fileupload, String datadir, String datadirprefix, ZipFile zipfile,
        boolean filterstyles, List<DocumentReference> savedDocuments, BatchImportLog log) throws XWikiException,
            ZipException, IOException
    {
        String fullName = newDoc.getPrefixedFullName();

        // get a couple of settings about the import here - we should get them in the caller function, but I don't want
        // to change the signature of the function.
        boolean attachmentMappingIsList = Boolean.parseBoolean("" + config.get("docfileislist"));
        boolean clearAttachmentNames = config.getClearFileNames();

        boolean withFile = false;
        String mappedFileValue = "";

        // if there is a mapping for the doc.file field
        String fileMapping = config.getFieldsMapping().get("doc.file");
        if (fileMapping != null) {
            mappedFileValue = data.get("doc.file");
            if (StringUtils.isNotEmpty(mappedFileValue)) {
                withFile = true;
            }
        }

        if (withFile) {
            // fileOk will be OK only if all attachments mapped are fine.
            // We use this logic so that we don't silently ignore some attachment that should be there and it's not.
            // If a value needs to be ignored, it should be fixed in the source file.
            boolean fileOk = false;
            // all paths of files to import, mapped by file name
            Map<String, String> paths = new HashMap<>();
            if (attachmentMappingIsList) {
                // get all file names mapped, as a list, and check each of them and collect only the ones that exist in
                // the configured file directory
                List<String> filesNames = getAsList(mappedFileValue, config.getListSeparator());
                // ALL FILES mapped should be OK for this to work, and there should be at least one file to import
                fileOk = filesNames.size() > 0;
                for (String fileName : filesNames) {
                    String filePath = getFilePath(datadir, datadirprefix, fileName);
                    if (checkFile(zipfile, filePath)) {
                        String fname = new File(filePath).getName();
                        paths.put(fname, filePath);
                    } else {
                        fileOk = fileOk && false;
                    }
                }
            } else {
                // use the single mapped file value as a single path
                String filePath = getFilePath(datadir, datadirprefix, mappedFileValue);
                if (checkFile(zipfile, filePath)) {
                    String fname = new File(filePath).getName();
                    paths.put(fname, filePath);
                    fileOk = true;
                }
            }
            // start importing the prepared list of paths
            if (fileOk) {
                debug("Ready to import row " + currentLine.toString() + "in page " + fullName
                    + " and at least one imported file is ok.");

                // if we're simulating we don't actually import the file, we only pretend
                if (simulation) {
                    log.logSave("simimportfileok", rowIndex, currentLine, fullName, paths.values());
                    savedDocuments.add(newDoc.getDocumentReference());
                } else {
                    Boolean attachmentsExist = true;
                    for(String fname : paths.keySet()) {
                        if (newDoc.getAttachment(fname) == null) {
                            attachmentsExist = false;
                            break;
                        }
                    }
                    // adding the file to the document only if fileupload is true
                    if (fileupload && attachmentsExist) {
                        debug("All files from " + paths.keySet() + " already exist in " + fullName);

                        // done here
                        // FIXME: this should depend on the overwrite file parameter or at least on the overwrite
                        // one (since overwritefile seems to be about the behavior of the document content when
                        // there are files attached)
                        newDoc.save();
                        savedDocuments.add(newDoc.getDocumentReference());
                        log.logSave("importduplicateattach", rowIndex, currentLine, fullName, paths.values());
                    } else {
                        // we're file uploading and we either have multiple values to upload or a single one and that
                        // one is a directory
                        if (fileupload && (paths.size() > 1
                            || (paths.size() == 1 && isDirectory(zipfile, paths.values().iterator().next())))) {
                            if (paths.size() == 1) {
                                addFiles(newDoc, paths.values().iterator().next(), clearAttachmentNames);
                            } else {
                                addFiles(newDoc, paths.values(), clearAttachmentNames);
                            }

                            // done here, we save pointed files in the file and we're done
                            newDoc.save();
                            savedDocuments.add(newDoc.getDocumentReference());
                            log.logSave("importfiledir", rowIndex, currentLine, fullName, paths.values());
                        } else {
                            // there is a single path to upload and that path is not a directory
                            String fname = paths.keySet().iterator().next();
                            String path = paths.values().iterator().next();
                            InputStream filedata = getFileData(zipfile, path);
                            if (filedata != null) {
                                try {
                                    if (fileupload) {
                                        addFile(newDoc, filedata, fname, clearAttachmentNames);
                                    }
                                    // TODO: why the hell are we doing this here?
                                    if (overwrite && overwritefile) {
                                        newDoc.setContent("");
                                    }

                                    // saving the document, in order to be able to do the import properly after
                                    newDoc.save();
                                    savedDocuments.add(newDoc.getDocumentReference());

                                    // launching the openoffice conversion
                                    if (fileimport) {
                                        if (!fname.toLowerCase().endsWith(".pdf")
                                            && (StringUtils.isEmpty(newDoc.getContent().trim())
                                                || (overwrite && overwritefile))) {
                                            boolean importResult = false;

                                            try {
                                                OfficeImporterScriptService officeImporter =
                                                    this.cm.getInstance(ScriptService.class, "officeimporter");
                                                // import the attachment in the content of the document
                                                // re-create the filedata stream so that it's reset to the beginning
                                                filedata.close();
                                                filedata = getFileData(zipfile, path);
                                                XDOMOfficeDocument xdomOfficeDoc = officeImporter.officeToXDOM(filedata,
                                                    fname, newDoc.getDocumentReference(), filterstyles);
                                                importResult = importResult =
                                                    officeImporter.save(xdomOfficeDoc, newDoc.getDocumentReference(),
                                                        newDoc.getSyntax().toIdString(), null, null, true);
                                            } catch (Exception e) {
                                                LOGGER.warn("Failed to import content from office file " + fname
                                                    + " to document " + fullName, e);
                                            }

                                            if (!importResult) {
                                                log.logSave("importofficefail", rowIndex, currentLine, fullName, path);
                                            } else {
                                                log.logSave("importoffice", rowIndex, currentLine, fullName, path);
                                            }

                                            // in case import was unsuccessful let's empty the content again
                                            // to be able to detect it
                                            Document newDoc2 =
                                                xcontext.getWiki().getDocument(newDoc.getDocumentReference(), xcontext)
                                                    .newDocument(xcontext);
                                            if (StringUtils.isEmpty(newDoc2.getContent().trim())) {
                                                newDoc2.setContent("");
                                                newDoc2.save();
                                            }
                                            // clean up open office temporary files
                                            cleanUp();
                                        }
                                    } else {
                                        log.logSave("importnooffice", rowIndex, currentLine, fullName, path);
                                    }
                                } finally {
                                    filedata.close();
                                }
                            } else {
                                log.logSave("importcannotreadfile", rowIndex, currentLine, fullName, path);
                            }
                        }
                    }
                }
            } else {
                log.logError("errornofile", rowIndex, currentLine, fullName, mappedFileValue);
                // TODO: this will leave the document unsaved because of the inexistent file, which impacts the data set
                // with the marshalDataToDocumentObjects function (because of the way doImport is written), so maybe
                // this should be configured by an error handling setting (skip row or skip value, for example), the
                // same as we should have for document data
            }
        } else {
            debug("Ready to import row " + currentLine.toString() + " in page " + fullName + " (no file attached).");

            // we should save the data
            if (simulation == false) {
                newDoc.save();
                log.logSave("importnofile", rowIndex, currentLine, fullName);
            } else {
                log.logSave("simimportnofile", rowIndex, currentLine, fullName);
            }
            savedDocuments.add(newDoc.getDocumentReference());
        }
    }

    /**
     * Prepares a log to put result in.
     *
     * @param logHint the hint of the log that needs to be used
     * @return the built log, looked up with the hint. If a logger cannot be looked up with the hint, a new instance is
     *         created of type {@link StringBatchImportLog}, so that we can properly fallback on some implementation.
     */
    protected BatchImportLog getLog(String logHint)
    {
        BatchImportLog log;
        try {
            if (logHint != null) {
                log = this.cm.getInstance(BatchImportLog.class, logHint);
            } else {
                log = this.cm.getInstance(BatchImportLog.class);
            }
        } catch (ComponentLookupException e1) {
            LOGGER.warn("Could not lookup a log instance, instantiating one manually");
            log = new StringBatchImportLog();
        }
        if (this.log) {
            log.setConsoleLogger(LOGGER);
        }

        return log;
    }

    /**
     * @param config the batch import configuration
     * @return the import file iterator corresponding to the type defined in the configuration.
     */
    protected ImportFileIterator getImportFileIterator(BatchImportConfiguration config) throws ComponentLookupException
    {
        String iteratorHint = config.getType();
        if (StringUtils.isEmpty(iteratorHint)) {
            return cm.getInstance(ImportFileIterator.class);
        } else {
            return cm.getInstance(ImportFileIterator.class, iteratorHint);
        }
    }

    protected List<RowDataPostprocessor> getRowDataPostprocessors() throws ComponentLookupException
    {
        List<RowDataPostprocessor> allProcessors = cm.getInstanceList(RowDataPostprocessor.class);
        Collections.sort(allProcessors, new Comparator<RowDataPostprocessor>()
        {
            @Override
            public int compare(RowDataPostprocessor o1, RowDataPostprocessor o2)
            {
                return (int) Math.signum(o1.getPriority() - o2.getPriority());
            }
        });
        return allProcessors;
    }

    protected XWikiContext getXWikiContext()
    {
        ExecutionContext ec = execution.getContext();
        XWikiContext xwikicontext = (XWikiContext) ec.getProperty(XWikiContext.EXECUTIONCONTEXT_KEY);
        return xwikicontext;
    }

    public static boolean getBooleanValue(Document doc, String propertyName, boolean defaultValue) {
        Integer value = (Integer) doc.getValue(propertyName);
        if (value == null) {
            return defaultValue;
        }
        return value.equals(0) ? false : true;
    }
}
