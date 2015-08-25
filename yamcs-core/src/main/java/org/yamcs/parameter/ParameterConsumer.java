package org.yamcs.parameter;

import java.util.List;

import org.yamcs.ContainerExtractionResult;
import org.yamcs.ParameterValue;

/**
 * Used by the ParameterRequestManager to deliver parameters
 * 
 * @author nm
 *
 */
public interface ParameterConsumer {
    void updateItems(int subscriptionId, List<ContainerExtractionResult> containers, List<ParameterValue> items);
}
