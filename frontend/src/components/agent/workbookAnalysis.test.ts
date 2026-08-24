import { describe, expect, it } from 'vitest'
import { workbookOptionsFromAnalysis } from './workbookAnalysis'

describe('workbookOptionsFromAnalysis', () => {
  it('maps the real analyze RPC sheet and column shape', () => {
    expect(workbookOptionsFromAnalysis({
      success: true,
      summary: 'Analyzed 2 sheets',
      sheets: [
        { name: 'Sales', columns: [{ index: '0', header: 'Region' }, { index: '1', header: 'Owner' }] },
        { name: 'Costs', columns: [{ index: '0', header: 'Department' }] },
      ],
    })).toEqual({
      Sales: ['Region', 'Owner'],
      Costs: ['Department'],
    })
  })

  it('surfaces an in-band plugin failure instead of showing empty selectors', () => {
    expect(() => workbookOptionsFromAnalysis({
      success: false,
      summary: 'Workbook is unreadable',
    })).toThrow('Workbook is unreadable')
  })

  it('ignores blank headers and malformed sheets', () => {
    expect(workbookOptionsFromAnalysis({
      success: true,
      sheets: [
        { name: '', columns: [{ header: 'Ignored' }] },
        { name: 'Sheet1', columns: [{ header: '' }, { header: 'Code' }, { header: 'Code' }] },
      ],
    })).toEqual({ Sheet1: ['Code'] })
  })
})
