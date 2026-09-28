import copy, json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
ROOT=Path(__file__).resolve().parents[2]
SCHEMA=json.loads((ROOT/'contracts/schemas/v1/entity-topology.schema.json').read_text())
def fixture(): return json.loads((ROOT/'contracts/examples/entity-topology.json').read_text())
def validate(v): Draft202012Validator(SCHEMA,format_checker=FormatChecker()).validate(v)
def test_labeled_topology_and_empty_neighborhood():
    v=fixture();validate(v);v['nodes']=v['nodes'][:1];v['edges']=[];validate(v)
@pytest.mark.parametrize('patch',[{'permission':'admin'},{'limit':51},{'coverage':'inferred'},{'nodes':[]},{'truncated':'false'}])
def test_closed_scope_and_budgets(patch):
    v=fixture();v.update(patch)
    with pytest.raises(ValidationError):validate(v)
@pytest.mark.parametrize('patch',[{'dataMode':'real'},{'sourceRef':'secret'},{'validFrom':'yesterday'},{'from':'host-1'}])
def test_relation_origin_and_no_raw_reference(patch):
    v=fixture();v['edges'][0].update(patch)
    with pytest.raises(ValidationError):validate(v)
