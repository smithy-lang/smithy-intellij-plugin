$version: "2.1"

namespace example

use foo.bar#region

@pattern(#re "^\d{5}$")
string Zip

@pattern(#re """
    ^\d{5}(-\d{4})?$
    """)
string ZipExtended

@example(#hex "48 65 6c 6c 6f")
blob Data

structure WithTag {
    id: region
}
