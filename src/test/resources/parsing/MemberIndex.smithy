$version: "2.1"

namespace example

structure Record {
    1. id: String
    2. value: String
}

structure WithElided with [Record] {
    1. $id
}

list Names {
    1. member: String
}
