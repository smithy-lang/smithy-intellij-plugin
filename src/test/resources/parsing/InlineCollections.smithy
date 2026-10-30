$version: "2.1"

namespace example

structure Foo {
    names: [String]
    tags: {String: String}
    accounts: {PersonId: [Account]}
}

string PersonId

structure Account {
    id: String
}
