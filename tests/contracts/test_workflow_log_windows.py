import copy
import json
from pathlib import Path
import pytest
from jsonschema import ValidationError
from test_workflow import validate
ROOT=Path(__file__).resolve().parents[2]

def example(kind):return json.loads((ROOT/f'contracts/examples/v2/workflow-log-window-{kind}.json').read_text(encoding='utf-8'))

@pytest.mark.parametrize('kind',['record','data'])
def test_server_window_examples(kind):validate('workflow-log-window-'+kind,example(kind))

@pytest.mark.parametrize('kind',['record','data'])
@pytest.mark.parametrize('key',['tenantId','authority','endpoint','sql','samples'])
def test_internal_read_data_rejects_identity_and_execution_extensions(kind,key):
    with pytest.raises(ValidationError):validate('workflow-log-window-'+kind,{**example(kind),key:'forged'})

@pytest.mark.parametrize('patch',[dict(index=-1),dict(index=1000),dict(position='bad'),dict(body=' '),dict(body='x'*2049),dict(traceId='bad'),dict(eventTime='tomorrow')])
def test_stable_record_is_bounded_and_normalized(patch):
    with pytest.raises(ValidationError):validate('workflow-log-window-record',{**example('record'),**patch})

def test_bulk_limit_is_distinct_from_sample_limit():
    row=example('record');row['index']=999;validate('workflow-log-window-record',row)
    data=example('data');data['inputCount']=1000;data['filtered']=0;data['records']=[copy.deepcopy(row) for _ in range(1000)];validate('workflow-log-window-data',data)
    data['records'].append(row)
    with pytest.raises(ValidationError):validate('workflow-log-window-data',data)

@pytest.mark.parametrize('patch',[dict(inputCount=1001),dict(filtered=-1),{'from':'bad'},dict(digest='sha256:x')])
def test_window_metadata_is_closed_and_bounded(patch):
    with pytest.raises(ValidationError):validate('workflow-log-window-data',{**example('data'),**patch})
