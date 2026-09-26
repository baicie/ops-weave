import json
import pytest
from jsonschema import ValidationError
from test_entity_page import ROOT, validate

def example(name): return json.loads((ROOT / f'contracts/examples/{name}.json').read_text())

@pytest.mark.parametrize('field,value',[('tenantId','other'),('allowPurge',True),('ids',[]),('asOf','2026-09-26T12:00:00.001Z'),('requestId','../run'),('previewDigest','sha256:'+'a'*64+'\n'),('policyDigest','x')])
def test_apply_rejects_authority_and_invalid_binding(field,value):
    v=example('ai-retention-apply');v[field]=value
    with pytest.raises(ValidationError):validate('ai-retention-apply',v)

@pytest.mark.parametrize('field,value',[('insightDays',0),('evidenceDays',3651),('auditDays',-1),('batchSize',101),('allowPurge','true'),('version','v1\n'),('heldIncidents',['not-a-uuid']),('schedule','automatic')])
def test_operator_policy_bounds(field,value):
    v=example('ai-retention-policies');v['policies'][0][field]=value
    with pytest.raises(ValidationError):validate('ai-retention-policies',v)

@pytest.mark.parametrize('mutation',['kind','negative','extra','duplicate','overflow'])
def test_preview_is_closed_and_bounded(mutation):
    v=example('ai-retention-preview');b=v['preview']['batches'][1]
    if mutation=='kind':b['kind']='SQL'
    if mutation=='negative':b['logicalBytes']=-1
    if mutation=='extra':v['sql']='delete'
    if mutation=='duplicate':b['ids']=['78977834-7381-4524-9a08-b028f979edc1']*2
    if mutation=='overflow':b['logicalBytes']=14000001
    with pytest.raises(ValidationError):validate('ai-retention-preview',v)
