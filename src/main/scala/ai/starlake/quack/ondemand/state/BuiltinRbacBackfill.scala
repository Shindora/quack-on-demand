package ai.starlake.quack.ondemand.state

import com.typesafe.scalalogging.LazyLogging

/** Boot step: every tenant carries the built-ins, and a pristine legacy `admin` role is folded into
  * `qod_all_tables`. Idempotent and HA-safe (each tenant is one transaction of ON CONFLICT-safe
  * writes, the fold one row-locked transaction), so every replica runs it on every boot. Runs
  * before PoolSupervisor loads its caches.
  */
object BuiltinRbacBackfill extends LazyLogging:
  def run(store: ControlPlaneStore): Unit =
    store.listTenants().foreach { t =>
      store.ensureBuiltins(t.id)
      if store.foldLegacyAdminRole(t.id) then
        logger.info(s"tenant ${t.id}: legacy admin role folded into ${BuiltinRbac.AllTables}")
    }
