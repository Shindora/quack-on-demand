import { useParams } from 'react-router-dom';
import Breadcrumb from '../components/Breadcrumb';
import PoolDetailBody from '../components/PoolDetailBody';
import { poolsPath, tenantHome } from '../nav/links';

export default function PoolDetail() {
  const { tenant, tenantDb, pool } = useParams<{ tenant: string; tenantDb: string; pool: string }>();

  if (!tenant || !tenantDb || !pool) {
    return <p style={{ color: 'red' }}>Missing tenant / tenantDb / pool in URL.</p>;
  }

  return (
    <div>
      <Breadcrumb
        items={[
          { label: tenant, to: tenantHome(tenant) },
          { label: 'Pools', to: poolsPath(tenant) },
          { label: tenantDb },
          { label: pool },
        ]}
      />
      <PoolDetailBody tenant={tenant} tenantDb={tenantDb} pool={pool} />
    </div>
  );
}
