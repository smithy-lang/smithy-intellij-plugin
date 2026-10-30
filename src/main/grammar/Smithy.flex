package software.amazon.smithy.intellij;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;

import static com.intellij.psi.TokenType.BAD_CHARACTER;
import static com.intellij.psi.TokenType.WHITE_SPACE;
import static software.amazon.smithy.intellij.psi.SmithyTypes.*;

%%

%{
  public _SmithyLexer() {
    this((java.io.Reader)null);
  }
%}

%public
%class _SmithyLexer
%implements FlexLexer
%function advance
%type IElementType
%unicode

EOL=\R
WHITE_SPACE=\s+

TOKEN_BOOLEAN=true|false
TOKEN_SIMPLE_TYPE_NAME=blob|boolean|document|string|byte|short|integer|long|float|double|bigInteger|bigDecimal|timestamp
TOKEN_SYMBOL=((_+[A-Za-z0-9])|[A-Za-z])[A-Za-z0-9_]*
TOKEN_MEMBER_INDEX=[1-9][0-9]*\.
TOKEN_NUMBER=-?(0|([1-9][0-9]*))(\.[0-9]+)?(e[+-]?[0-9]+)?
TOKEN_STRING=\"((\\['bfnrt/\"\\])|(\\u[0-9A-Fa-f]{4})|(\\?[ \t!#-\[\]-\U10FFFF])|(\\?\r?\n))*\"
TOKEN_TEXT_BLOCK=\"\"\"\n((\"[^\"])|(\"\"[^\"])|(\\['bfnrt/\"\\])|(\\u[0-9A-Fa-f]{4})|(\\?[ \t!#-\[\]-\U10FFFF])|(\\[\"]{3})|(\\?\r?\n))*\"\"\"
//Tagged string literal prefix (IDL 2.1), e.g. #re, #b, #hex, #timestamp. Only recognized when followed by a
//string/text-block (via JFlex trailing context in the rule) so shape-id fragments like "foo#region" are unaffected.
TOKEN_TAG=#(re|b|hex|timestamp)[ \t]*
TOKEN_LINE_COMMENT=("//")|("//"[^/\n][\t -\U10FFFF]*)
TOKEN_DOCUMENTATION_LINE="///"[\t -\U10FFFF]*
TOKEN_INCOMPLETE_STRING=\"((\\['bfnrt/\"\\])|(\\u[0-9A-Fa-f]{4})|(\\?[ \t!#-\[\]-\U10FFFF]))+
TOKEN_INCOMPLETE_TEXT_BLOCK=\"\"\"\n((\\['bfnrt/\"\\])|(\\u[0-9A-Fa-f]{4})|(\\?[ \t!#-\[\]-\U10FFFF])|(\\[\"]{3})|(\\?\r?\n))+

%%
<YYINITIAL> {
  {WHITE_SPACE}                       { return WHITE_SPACE; }

  "("                                 { return TOKEN_OPEN_PAREN; }
  ")"                                 { return TOKEN_CLOSE_PAREN; }
  "{"                                 { return TOKEN_OPEN_BRACE; }
  "}"                                 { return TOKEN_CLOSE_BRACE; }
  "["                                 { return TOKEN_OPEN_BRACKET; }
  "]"                                 { return TOKEN_CLOSE_BRACKET; }
  ":="                                { return TOKEN_WALRUS; }
  ":"                                 { return TOKEN_COLON; }
  ","                                 { return TOKEN_COMMA; }
  "="                                 { return TOKEN_EQUALS; }
  "."                                 { return TOKEN_PERIOD; }
  "$"                                 { return TOKEN_DOLLAR_SIGN; }
  "@"                                 { return TOKEN_AT; }
  {TOKEN_TAG}/\"                       { return TOKEN_TAG; }
  "#"                                 { return TOKEN_HASH; }
  "apply"                             { return TOKEN_APPLY; }
  "use"                               { return TOKEN_USE; }
  "enum"                              { return TOKEN_ENUM; }
  "intEnum"                           { return TOKEN_INT_ENUM; }
  "list"                              { return TOKEN_LIST; }
  "set"                               { return TOKEN_SET; }
  "map"                               { return TOKEN_MAP; }
  "metadata"                          { return TOKEN_METADATA; }
  "namespace"                         { return TOKEN_NAMESPACE; }
  "structure"                         { return TOKEN_STRUCTURE; }
  "union"                             { return TOKEN_UNION; }
  "service"                           { return TOKEN_SERVICE; }
  "version"                           { return TOKEN_VERSION; }
  "operations"                        { return TOKEN_OPERATIONS; }
  "resources"                         { return TOKEN_RESOURCES; }
  "rename"                            { return TOKEN_RENAME; }
  "operation"                         { return TOKEN_OPERATION; }
  "input"                             { return TOKEN_INPUT; }
  "output"                            { return TOKEN_OUTPUT; }
  "errors"                            { return TOKEN_ERRORS; }
  "resource"                          { return TOKEN_RESOURCE; }
  "identifiers"                       { return TOKEN_IDENTIFIERS; }
  "properties"                        { return TOKEN_PROPERTIES; }
  "create"                            { return TOKEN_CREATE; }
  "put"                               { return TOKEN_PUT; }
  "read"                              { return TOKEN_READ; }
  "update"                            { return TOKEN_UPDATE; }
  "delete"                            { return TOKEN_DELETE; }
  "collectionOperations"              { return TOKEN_COLLECTION_OPERATIONS; }
  "for"                               { return TOKEN_FOR; }
  "with"                              { return TOKEN_WITH; }
  "null"                              { return TOKEN_NULL; }

  {TOKEN_BOOLEAN}                     { return TOKEN_BOOLEAN; }
  {TOKEN_SIMPLE_TYPE_NAME}            { return TOKEN_SIMPLE_TYPE_NAME; }
  {TOKEN_SYMBOL}                      { return TOKEN_SYMBOL; }
  {TOKEN_MEMBER_INDEX}                { return TOKEN_MEMBER_INDEX; }
  {TOKEN_NUMBER}                      { return TOKEN_NUMBER; }
  {TOKEN_STRING}                      { return TOKEN_STRING; }
  {TOKEN_TEXT_BLOCK}                  { return TOKEN_TEXT_BLOCK; }
  {TOKEN_LINE_COMMENT}                { return TOKEN_LINE_COMMENT; }
  {TOKEN_DOCUMENTATION_LINE}          { return TOKEN_DOCUMENTATION_LINE; }
  {TOKEN_INCOMPLETE_STRING}           { return TOKEN_INCOMPLETE_STRING; }
  {TOKEN_INCOMPLETE_TEXT_BLOCK}       { return TOKEN_INCOMPLETE_TEXT_BLOCK; }

}

[^] { return BAD_CHARACTER; }
