/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.core.parse.core.rule.jaxb.entity.extractor;

import lombok.Getter;
import org.apache.shardingsphere.core.parse.core.rule.jaxb.entity.RuleDefinitionEntity;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import java.util.Collection;
import java.util.LinkedList;

/**
 * Extractor rule definition entity for JAXB.
 *
 * @author zhangliang
 */
@XmlRootElement(name = "extractor-rule-definition")
@Getter
public final class ExtractorRuleDefinitionEntity implements RuleDefinitionEntity {
    
    @XmlElement(name = "extractor-rule")
    private Collection<ExtractorRuleEntity> rules = new LinkedList<>();
}
