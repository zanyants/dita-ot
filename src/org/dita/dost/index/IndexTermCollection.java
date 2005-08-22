/*
 * (c) Copyright IBM Corp. 2005 All Rights Reserved.
 */
package org.dita.dost.index;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.dita.dost.module.Content;
import org.dita.dost.module.ContentImpl;
import org.dita.dost.util.Constants;
import org.dita.dost.writer.CHMIndexWriter;
import org.dita.dost.writer.JavaHelpIndexWriter;

/**
 * This class is a collection of index term.
 * 
 * @version 1.0 2005-05-18
 * 
 * @author Wu, Zhi Qiang
 */
public class IndexTermCollection {
	/** The list of all index term */
	private static List termList = new ArrayList(Constants.INT_16);

	/** The type of index term */
	private static String indexType = null;

	/** The output file name of index term without extension */
	private static String outputFileRoot = null;

	/**
	 * Private constructor used to forbid instance.
	 */
	private IndexTermCollection() {
	}

	/**
	 * Get the index type.
	 * 
	 * @return
	 */
	public static String getIndexType() {
		return IndexTermCollection.indexType;
	}

	/**
	 * Set the index type.
	 * 
	 * @param type
	 *            The indexType to set.
	 */
	public static void setIndexType(String type) {
		IndexTermCollection.indexType = type;
	}

	/**
	 * All a new term into the collection.
	 * 
	 * @param term
	 */
	public static void addTerm(IndexTerm term) {
		int i = 0;
		int termNum = termList.size();

		for (; i < termNum; i++) {
			IndexTerm indexTerm = (IndexTerm) termList.get(i);
			if (indexTerm.equals(term)) {
				return;
			}

			if (indexTerm.getSubTerms().equals(term.getSubTerms())
					&& indexTerm.getTermName().equals(term.getTermName())) {
				indexTerm.addTargets(term.getTargetList());
				break;
			}
		}

		if (i == termNum) {
			termList.add(term);
		}
	}

	/**
	 * Get all the term list from the collection.
	 * 
	 * @return
	 */
	public static List getTermList() {
		return termList;
	}

	/**
	 * Sort term list extracted from dita files base on Locale.
	 */
	public static void sort() {
		if (IndexTerm.getTermLocale() == null) {
			IndexTerm.setTermLocale(new Locale(Constants.LANGUAGE_EN,
					Constants.COUNTRY_US));
		}

		Collections.sort(termList);
	}

	/**
	 * Output index terms into index file.
	 * 
	 * @throws IOException
	 * @throws IOException
	 */
	public static void outputTerms() throws IOException {
		OutputStream outputStream = null;

		try {

			if (Constants.INDEX_TYPE_HTMLHELP.equalsIgnoreCase(indexType)) {
				CHMIndexWriter indexWriter = new CHMIndexWriter();
				Content content = new ContentImpl();
				outputStream = new FileOutputStream(new StringBuffer(
						IndexTermCollection.outputFileRoot).append(".hhk")
						.toString());

				content.setCollection(IndexTermCollection.getTermList());
				indexWriter.setContent(content);
				indexWriter.write(outputStream);
			} else if (Constants.INDEX_TYPE_JAVAHELP
					.equalsIgnoreCase(indexType)) {
				JavaHelpIndexWriter indexWriter = new JavaHelpIndexWriter();
				Content content = new ContentImpl();
				outputStream = new FileOutputStream(new StringBuffer(
						IndexTermCollection.outputFileRoot)
						.append("_index.xml").toString());

				content.setCollection(IndexTermCollection.getTermList());
				indexWriter.setContent(content);
				indexWriter.write(outputStream);
			}

			System.out.println(new StringBuffer().append(
					IndexTermCollection.getTermList().size()).append(
					" index terms were extracted.").toString());
		} finally {
			if (outputStream != null) {
				outputStream.close();
			}
		}
	}

	/**
	 * Set the output file
	 * 
	 * @param fileRoot
	 *            The outputFile to set.
	 */
	public static void setOutputFileRoot(String fileRoot) {
		IndexTermCollection.outputFileRoot = fileRoot;
	}

}
