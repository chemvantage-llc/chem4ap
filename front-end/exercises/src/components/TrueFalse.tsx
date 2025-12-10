import React from 'react';

interface TrueFalseProps {
    handleSubmit: (event: React.FormEvent<HTMLFormElement>) => void;
    setStudentAnswer: (studentAnswer: string) => void;
}

const TrueFalse: React.FC<TrueFalseProps> = ({ handleSubmit, setStudentAnswer }) => {
    return (
        <form onSubmit={handleSubmit} style={{ display: 'flex', alignItems: 'center' }}>
        <div className="form-check" style={{ flex: 1 }}>
        <label style={{ display: 'block' }}>
          <input
          className="form-check-input"
          type="radio"
          required
          name="true_false"
          value="true"
          onChange={(e) => { setStudentAnswer(e.target.value); }}
          />
          True
        </label>
        <label style={{ display: 'block' }}>
          <input
          className="form-check-input"
          type="radio"
          required
          name="true_false"
          value="false"
          onChange={(e) => setStudentAnswer(e.target.value)}
          />
          False
        </label>
        </div>
        <div style={{ flex: 1, paddingLeft: '50px', textAlign: 'center' }}>
        <button className="btn btn-primary" type="submit">Submit</button>
        </div>
      </form>
    );
}

export default TrueFalse;