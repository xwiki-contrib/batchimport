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
import org.xwiki.component.annotation.Component;

/**
 * Row data postprocessor that processes constants keywords in the mappings, like "Constant:value" and sets the "value"
 * as the value of that column.
 * 
 * @version $Id$
 */
@Component("constants")
public class ConstantsRowDataPostprocessor implements RowDataPostprocessor
{
    /**
     * The prefix of the mapping that marks that the value of the mapping is a constant value, not a column from the
     * file.
     */
    public static final String PREFIX = "Constant:";

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
            // if the header starts with the constant prefix, recompute the value as the constant value from the mapping
            // and update it in the data line
            if (header.startsWith(PREFIX)) {
                String newValue = header.substring(PREFIX.length());
                data.put(xwikiField, newValue);
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
        return 20;
    }

}
