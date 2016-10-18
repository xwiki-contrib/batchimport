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
package org.xwiki.batchimport;

import java.util.List;
import java.util.Map;

import org.xwiki.component.annotation.ComponentRole;

/**
 * Postprocessor of the data of a row already read from the file, allowing to do all sorts of manipulations to the data
 * already read from a row, including completely changing it. It should be used mainly for modifications of the data,
 * not for replacing the data that was read, although it is, actually possible to do it.
 * 
 * @version $Id$
 */
@ComponentRole
public interface RowDataPostprocessor
{
    /**
     * Postprocesses a map of data that was already extracted from the row. This function should alter the {@code data}
     * in place.
     * 
     * @param data the data that was already read from the {@code row} according to the {@code mapping}, that should be
     *            updated by this postprocessor.
     * @param row the original row from which the data was extracted according to the mapping
     * @param rowIndex the index of the row being processed
     * @param mapping the mapping between headers and XWiki fields
     * @param headers all the headers from the file to read (not only the mapped ones)
     * @param config the original config of this batch importer, for any other information that might be needed
     */
    public void postProcessRow(Map<String, String> data, List<String> row, int rowIndex, Map<String, String> mapping,
        List<String> headers, BatchImportConfiguration config);

    /**
     * @return the priority of this processor. Note that priorities can be positive or negative, and are double values.
     *         Lower vealue means higher priority.
     */
    public double getPriority();
}
