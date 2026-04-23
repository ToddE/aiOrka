### Business Source License 1.1

**Parameters**
* **Licensor**: PluralFusion INC
* **Software**: aiOrka and all its components (including Kotlin, Python, Go, and Rust bindings).
* **Change Date**: 2030-04-23
* **Change License**: Apache License, Version 2.0
* **Additional Use Grant**: You may make use of the Software for any purpose, including internal commercial use, provided that you do not use the Software to provide a Managed AI Orchestration Service. A "Managed AI Orchestration Service" is defined as a service where you offer the core functionality of the Software to third parties as a hosted or managed platform for AI model routing, policy management, or AI brokerage.

---

**License Text**

Copyright © 2026 PluralFusion INC. All rights reserved.

"Business Source License" is a trademark of MariaDB Corporation AB, used under license.

Each Licensor hereby grants you a worldwide, royalty-free, non-exclusive, semi-permissive license to combine, modify, and distribute the Software and create derivative works of the Software, subject to the following conditions:

1.  The Software and any derivative works of the Software you distribute must include a copy of the license, in the form of this file, and be governed by this license.
2.  Any use of the Software must comply with the **Additional Use Grant** specified above.
3.  Effective on the **Change Date**, or the fourth anniversary of the first public release of a specific version of the Software under this license, whichever is earlier, the **Change License** specified above shall apply to that version of the Software and any derivative works.
4.  This license does not grant you any right to use any trademark, service mark, or logo of the Licensor.
5.  **TO THE EXTENT PERMITTED BY APPLICABLE LAW, THE SOFTWARE IS PROVIDED "AS IS" WITHOUT WARRANTIES OF ANY KIND, EITHER EXPRESS OR IMPLIED. THE LICENSOR SHALL NOT BE LIABLE FOR ANY DAMAGES ARISING OUT OF THE USE OR INABILITY TO USE THE SOFTWARE.**

---

### Implementation Steps

1.  **Update the README**: Update your `README.md` to reflect the change from Apache 2.0 to BSL 1.1.
2.  **Add the License File**: Replace your current license file with the text above.
3.  **Update Headers**: While not strictly required for every file, it is best practice to update the top-level comments in files like `AiOrka.kt` or `README.md` to state: *"Licensed under the Business Source License 1.1. See LICENSE for details."*.
4.  **Define the "Managed Service"**: Ensure the **Additional Use Grant** in the parameters above is specific enough to protect your "Managed Registry API" and "Cloud Optimizer" goals while still being clear to users.