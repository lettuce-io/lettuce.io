/*
 * Copyright 2017-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.lettuce

import javax.xml.bind.annotation.*

/**
 * @author Mark Paluch
 */
@XmlRootElement(name = "metadata")
@XmlAccessorType(XmlAccessType.FIELD)
class MavenMetadata {

    @XmlElement(name = "versioning")
    var versioning: Versioning? = null
        get() = field

}

@XmlAccessorType(XmlAccessType.FIELD)
class Versioning {

    @XmlElementWrapper(name = "versions")
    @XmlElement(name = "version")
    var versions: List<String> = mutableListOf()
        get() = field

    var snapshot: Snapshot? = null
        get() = field

}

@XmlAccessorType(XmlAccessType.FIELD)
class Snapshot {

    var timestamp: String? = null
        get() = field

    var buildNumber: String? = null
        get() = field
}
