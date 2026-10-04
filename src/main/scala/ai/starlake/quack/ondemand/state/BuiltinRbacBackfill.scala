package ai.starlake.quack.ondemand.state

import com.typesafe.scalalogging.LazyLogging

import scala.util.control.NonFatal

/** Boot step: every tenant carries the built-ins, and a pristine legacy `admin` role is folded into
  * `qod_all_tables`. Idempotent and HA-safe (each tenant is one transaction of ON CONFLICT-safe
  * writes, the fold one row-locked transaction), so every replica runs it on every boot. A failing
  * tenant is logged and skipped, never fatal. Runs before PoolSupervisor loads its caches.
  */
object BuiltinRbacBackfill extends LazyLogging:
  def run(store: ControlPlaneStore): Unit =
    store.listTenants().foreach { t =>
      // One tenant's failure must not fail boot: log it and move on; the next boot retries.
      try
        store.ensureBuiltins(t.id)
        if store.foldLegacyAdminRole(t.id) then
          logger.info(s"tenant ${t.id}: legacy admin role folded into ${BuiltinRbac.AllTables}")
      catch
        case NonFatal(e) =>
          logger.error(s"tenant ${t.id}: built-in RBAC backfill failed: ${e.getMessage}", e)
    }
