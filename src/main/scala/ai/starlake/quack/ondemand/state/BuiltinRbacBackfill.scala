package ai.starlake.quack.ondemand.state

import ai.starlake.quack.ondemand.ha.StateChangePublisher
import com.typesafe.scalalogging.LazyLogging

import scala.util.control.NonFatal

/** Boot step: every tenant carries the built-ins, and a pristine legacy `admin` role is folded into
  * `qod_all_tables`. Idempotent and HA-safe (each tenant is one transaction of ON CONFLICT-safe
  * writes, the fold one row-locked transaction), so every replica runs it on every boot. A failing
  * tenant is logged and skipped, never fatal. Runs before PoolSupervisor loads its caches.
  *
  * When any tenant changed, one `rbacChanged()` goes out at the end: replicas already running (a
  * rolling restart of an HA deployment) would otherwise keep a resolver without the new rows until
  * their periodic snapshot refresh. Returns whether anything changed.
  */
object BuiltinRbacBackfill extends LazyLogging:
  def run(store: ControlPlaneStore, publish: StateChangePublisher): Boolean =
    val changed = store
      .listTenants()
      .map { t =>
        // One tenant's failure must not fail boot: log it and move on; the next boot retries.
        try
          val ensured = store.ensureBuiltins(t.id).changed
          val folded  = store.foldLegacyAdminRole(t.id)
          if folded then
            logger.info(s"tenant ${t.id}: legacy admin role folded into ${BuiltinRbac.AllTables}")
          ensured || folded
        catch
          case NonFatal(e) =>
            logger.error(s"tenant ${t.id}: built-in RBAC backfill failed: ${e.getMessage}", e)
            false
      }
      .exists(identity)
    if changed then publish.rbacChanged()
    changed
