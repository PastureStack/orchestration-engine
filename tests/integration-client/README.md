# Maintained integration API client

The integration suites need a small dynamic client for the preserved Cattle
GDAPI surface. The former dependencies (`cattle==0.5.4` and a 2017 commit from
the archived `rancher/gdapi-python` repository) are no longer installed.

This directory keeps only the behavior the suites use: schema discovery,
dynamic CRUD/action methods, bounded HTTP requests, conflict retry, environment
configuration, and Cattle transition waiting. The retired CLI and table
formatter are deliberately excluded. Both integration suites import this one
copy through `PYTHONPATH`.

The implementation is derived from the MIT-licensed `gdapi-python` and
`cattle-cli` clients. Original notices are retained in `LICENSE.txt`.
