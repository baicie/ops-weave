import json
from pathlib import Path
import pytest
from jsonschema import ValidationError
from test_pipeline import validate

ROOT = Path(__file__).resolve().parents[2]
def sample():
    return json.loads((ROOT / 'contracts/examples/metric-series-page.json').read_text())

def counter_page():
    body=sample()
    body['derivation']={'kind':'counter-rate','resetPolicy':'reset-counts-from-zero'}
    row=body['series'][0]
    row['counterRates']=[
        {'t':row['points'][0][0]+10_000,'rate':'5.000000','counterReset':False},
        {'t':row['points'][0][0]+20_000,'rate':'2.000000','counterReset':True},
    ]
    return body

def test_a_raw_page_stays_valid_without_a_derivation():
    validate('metric-series-page',sample())

def test_a_counter_page_carries_rates_and_the_reset_policy():
    validate('metric-series-page',counter_page())

@pytest.mark.parametrize('kind,policy',[('rate','reset-counts-from-zero'),('counter-rate','guess'),('counter-rate','')])
def test_the_derivation_is_a_closed_contract(kind,policy):
    body=counter_page();body['derivation']={'kind':kind,'resetPolicy':policy}
    with pytest.raises(ValidationError):validate('metric-series-page',body)

def test_a_counter_page_must_state_its_rates():
    body=counter_page();del body['series'][0]['counterRates']
    with pytest.raises(ValidationError):validate('metric-series-page',body)

@pytest.mark.parametrize('rate',['-1.0','1e3','',' 1.0'])
def test_a_derived_rate_is_never_negative_or_free_form(rate):
    body=counter_page();body['series'][0]['counterRates'][0]['rate']=rate
    with pytest.raises(ValidationError):validate('metric-series-page',body)

@pytest.mark.parametrize('value',['true',1,None])
def test_a_reset_marker_is_a_boolean(value):
    body=counter_page();body['series'][0]['counterRates'][1]['counterReset']=value
    with pytest.raises(ValidationError):validate('metric-series-page',body)

def test_a_derived_interval_needs_its_time():
    body=counter_page();del body['series'][0]['counterRates'][0]['t']
    with pytest.raises(ValidationError):validate('metric-series-page',body)
