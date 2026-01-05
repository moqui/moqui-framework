# README -- Custom Changes Applied to Vendor Branch

This repository contains a set of enhancements and stability fixes
applied on top of the upstream Moqui Framework.

## Summary of Enhancements

### [CustomChanges.patch](https://github.com/hotwax/moqui-framework/patches/CustomChanges.patch)
These changes primarily focus on **resource safety**, **dynamic view
improvements**, and **CSV parsing enhancements**, while maintaining full
compatibility with upstream behavior.

------------------------------------------------------------------------

#### 1. Migration to Try-With-Resources (TWR)

All code paths that previously depended on manually closing resources
have been modernized to use Java's try-with-resources pattern.

Benefits: - Eliminates leaked resources
- Ensures proper cleanup on errors
- Concurrency-safe and more predictable
- Reduces boilerplate code

------------------------------------------------------------------------

#### 2. Enhancements to Dynamic View Construction

Additional capabilities were added to the dynamic view builder,
enabling:

-   Entity-level conditions
-   Nested and combined where clauses
-   HAVING clause support
-   Sub-select support
-   Extended alias options (including aggregate-related attributes)

Benefits: - Enables more advanced view-entity construction
- Reduces dependency on custom SQL
- Supports richer reporting and data selection logic

------------------------------------------------------------------------

#### 3. CSV Escape Character Support

Full support for specifying a CSV escape character, alongside delimiter,
quote, and comment characters.

Benefits: - More accurate CSV parsing
- Proper handling of escaped delimiters and quotes
- Better compatibility with external CSV sources

------------------------------------------------------------------------

#### 4. Improved Error Handling & Stability Fixes

General improvements include:

-   Prevention of lock-wait issues in login-key creation flows
-   Safer parsing of XML and template files
-   Improved WebSocket timeout and closed-channel handling
-   More reliable binary and text streaming

------------------------------------------------------------------------

#### 5. Internal Cleanup & Improvements

Various cleanup changes were applied to improve quality and
maintainability:

-   Simplified iterator patterns
-   Removal of duplicate cleanup logic
-   Consistent handling of resources across the framework
-   Minor logic adjustments and clearer error messages

------------------------------------------------------------------------

#### Commit References

(Add your commit hashes here.)
- Add support for CSV escape character to EntityDataLoaderImpl [#19](https://github.com/hotwax/moqui-framework/pull/19/files)
- Try with resource jdbc 
[#20](https://github.com/hotwax/moqui-framework/pull/20), [#21](https://github.com/hotwax/moqui-framework/pull/21), [#22](https://github.com/hotwax/moqui-framework/pull/22)
- Moved the elastic facade initialization before postFacadeInit call [#25](https://github.com/hotwax/moqui-framework/pull/25)
- Implemented auto closable interface for EntityQueryBuilder [#26](https://github.com/hotwax/moqui-framework/pull/26)
- EntityDynamicViewImpl enhancement for dynamic query construction [#28](https://github.com/hotwax/moqui-framework/pull/28)
- Improvment: Removed the 30 sec minimum idle timeout limit of RestClient [#30](https://github.com/hotwax/moqui-framework/pull/30)
- Fix premature EntityListIterator (ELI) closure in <iterate/> macro causing DB connection leak and stuck worker threads [#33](https://github.com/hotwax/moqui-framework/pull/33)


------------------------------------------------------------------------

#### Upstream / OOTB Reference

(Add the upstream PR link here.)
- Implemented withCloseable/try-with-resources where needed in the code… [#625](https://github.com/moqui/moqui-framework/pull/625/files)
- Use try-with-resources for JDBC AutoCloseable resources [#648](https://github.com/moqui/moqui-framework/pull/648)

------------------------------------------------------------------------

### [MyAddons.patch](https://github.com/hotwax/moqui-framework/patches/MyAddons.patch)
Added support for downloading components from private Git repositories:
- Introduced a new private="Y" attribute for components.
- When a component is marked as private:
  - The system now bypasses ant.get (which doesn’t support private repos).
  - Automatically switches to cloning using the system’s configured Git credentials (e.g., .netrc).
  - Handles version normalization (1.0.0 → v1.0.0).
  - Ensures consistent behavior for both release/current and git component types.
  - Public repositories continue using the existing ant.get and Grgit.clone flow.

### Benefit
Enables seamless retrieval of private components during build/deployment without changing the existing addon structure.

------------------------------------------------------------------------
### [JobRunnerStuck.patch](./JobRunnerStuck.patch)
Added improvements to prevent scheduled jobs from getting stuck.

**Job Runner**: Updates job status and locks in a new transaction. This ensures jobs are correctly marked as "Failed" instead of sticking in "Running" when database errors occur.

**Benefit**: Improves stability by ensuring jobs don't get stuck after a crash.