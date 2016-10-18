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

import java.util.List;
import java.util.Map;

import org.xwiki.batchimport.BatchImportConfiguration;
import org.xwiki.batchimport.RowDataPostprocessor;

/**
 * Row data postprocessor that processes row index keywords in the mappings, like "Computed:rowIndex" or
 * "Computed:offsetRowIndex" and sets the row index or the offsetted row index as value for that field. The offset for
 * this processor is taken from the configuration, and it's the same that would be used for empty document names, if any
 * is set.
 * 
 * @version $Id$
 */
public class RowIndexRowDataPostprocessor implements RowDataPostprocessor
{
    /**
     * The value of the mapping that marks that the value of the mapping is the row index.
     */
    public static final String ROW_INDEX = "Computed:rowIndex";

    /**
     * The value of the mapping that marks that the value of the mapping is the row index + offset.
     */
    public static final String OFFSET_ROW_INDEX = "Computed:offsetRowIndex";

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.batchimport.RowDataPostprocessor#postProcessRow(java.util.Map, java.util.List, int, java.util.Map,
     *      java.util.List, org.xwiki.batchimport.BatchImportConfiguration)
     */
    @Override
    public void postProcessRow(Map<String, String> data, List<String> row, int rowIndex, Map<String, String> mapping,
        List<String> headers, BatchImportConfiguration config)
    {
        for (Map.Entry<String, String> fieldMapping : mapping.entrySet()) {
            String xwikiField = fieldMapping.getKey();
            String header = fieldMapping.getValue();
            // if the header is the row index or the offset row index, set the value of this field as the computed value
            if (header.equals(ROW_INDEX)) {
                data.put(xwikiField, Integer.toString(rowIndex));
            }
            if (header.equals(OFFSET_ROW_INDEX)) {
                data.put(xwikiField, Long.toString(config.getEmptyDocNameOffset() + rowIndex));
            }
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.batchimport.RowDataPostprocessor#getPriority()
     */
    @Override
    public double getPriority()
    {
        return 40;
    }

}
